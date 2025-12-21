package com.containermgmt.notifier.listener;

import com.containermgmt.notifier.config.NotificationConfigProperties;
import com.containermgmt.notifier.dto.StreamEvent;
import com.containermgmt.notifier.service.NotificationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.javalite.activejdbc.Base;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listener per Valkey Streams
 * Registra consumer groups e processa messaggi dagli stream configurati
 */
@Component
public class ValkeyStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger logger = LoggerFactory.getLogger(ValkeyStreamListener.class);

    @Autowired
    private NotificationConfigProperties config;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer;
    private final List<Subscription> subscriptions = new ArrayList<>();

    @PostConstruct
    public void init() {
        logger.info("Initializing Valkey Stream Listener");

        if (config.getEventMappings() == null || config.getEventMappings().isEmpty()) {
            logger.warn("No event mappings configured. Listener will not start.");
            return;
        }

        // Crea consumer groups se non esistono
        createConsumerGroups();

        // Avvia listener container
        startListenerContainer();

        logger.info("Valkey Stream Listener initialized with {} mappings",
            config.getEventMappings().size());
    }

    /**
     * Crea consumer groups per tutti gli stream configurati
     */
    private void createConsumerGroups() {
        for (NotificationConfigProperties.EventMapping mapping : config.getEventMappings()) {
            try {
                // Verifica se lo stream esiste, altrimenti crealo
                if (!streamExists(mapping.getStream())) {
                    logger.info("Stream {} does not exist, it will be created on first message",
                        mapping.getStream());
                }

                // Crea consumer group se non esiste
                // Usa offset "0" per leggere tutti i messaggi esistenti nello stream all'avvio
                try {
                    redisTemplate.opsForStream().createGroup(
                        mapping.getStream(),
                        ReadOffset.from("0"),
                        mapping.getConsumerGroup()
                    );
                    logger.info("Created consumer group: stream={}, group={}",
                        mapping.getStream(), mapping.getConsumerGroup());
                } catch (Exception e) {
                    // Group già esistente, ignora errore
                    logger.debug("Consumer group already exists: stream={}, group={}",
                        mapping.getStream(), mapping.getConsumerGroup());
                }

            } catch (Exception e) {
                logger.error("Error creating consumer group: stream={}, group={}, error={}",
                    mapping.getStream(), mapping.getConsumerGroup(), e.getMessage());
            }
        }
    }

    /**
     * Verifica se uno stream esiste
     */
    private boolean streamExists(String streamName) {
        try {
            Long length = redisTemplate.opsForStream().size(streamName);
            return length != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Avvia il listener container e registra subscription per ogni mapping
     */
    private void startListenerContainer() {
        // Crea listener container
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(1))
                .build();

        listenerContainer = StreamMessageListenerContainer.create(connectionFactory, options);

        // Ottieni nome consumer (hostname o configurato)
        String consumerName = getConsumerName();

        // Registra subscription per ogni mapping
        for (NotificationConfigProperties.EventMapping mapping : config.getEventMappings()) {
            StreamOffset<String> offset = StreamOffset.create(
                mapping.getStream(),
                ReadOffset.lastConsumed()
            );

            Consumer consumer = Consumer.from(
                mapping.getConsumerGroup(),
                mapping.getConsumerName() != null ? mapping.getConsumerName() : consumerName
            );

            Subscription subscription = listenerContainer.receive(
                consumer,
                offset,
                this
            );

            subscriptions.add(subscription);

            logger.info("Registered subscription: stream={}, group={}, consumer={}",
                mapping.getStream(), mapping.getConsumerGroup(), consumer.getName());
        }

        // Avvia container
        listenerContainer.start();
        logger.info("Stream listener container started");
    }

    /**
     * Ottiene il nome del consumer (hostname o default)
     */
    private String getConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            logger.warn("Could not get hostname, using default consumer name");
            return "notifier-consumer-" + System.currentTimeMillis();
        }
    }

    /**
     * Callback invocato quando arriva un messaggio dallo stream
     */
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String stream = message.getStream();
        RecordId messageId = message.getId();

        logger.debug("Received message: stream={}, messageId={}", stream, messageId);

        // Apri connessione ActiveJDBC per questo thread
        boolean connectionOpened = false;
        try {
            if (!Base.hasConnection()) {
                Base.open(driverClassName, dbUrl, dbUsername, dbPassword);
                connectionOpened = true;
                logger.debug("Opened ActiveJDBC connection for listener thread");
            }

            // Estrai payload dal messaggio e pulisci valori JSON serializzati
            Map<String, String> rawPayload = message.getValue();
            Map<String, String> payload = cleanJsonValues(rawPayload);

            // Estrai event type dal payload
            String eventType = payload.get(StreamEvent.EVENT_TYPE_FIELD);
            if (eventType == null) {
                logger.warn("Event type field '{}' not found in message payload", StreamEvent.EVENT_TYPE_FIELD);
                acknowledgeMessage(stream, findFirstMapping(stream), messageId);
                return;
            }

            // Trova il mapping per questo stream
            NotificationConfigProperties.EventMapping mapping = findMapping(stream, eventType);
            if (mapping == null) {
                logger.warn("No mapping found for stream: {}", stream);
                acknowledgeMessage(stream, findFirstMapping(stream), messageId);
                return;
            }            

            // Crea StreamEvent
            StreamEvent event = new StreamEvent(stream, messageId.getValue(), payload);
            event.setEventType(eventType);

            // Processa evento
            notificationService.processStreamEvent(event);

            // Acknowledge messaggio se configurato
            if (mapping.isAutoAck()) {
                acknowledgeMessage(stream, mapping, messageId);
            }

        } catch (Exception e) {
            logger.error("Error processing message: stream={}, messageId={}, error={}",
                stream, messageId, e.getMessage(), e);

            // Acknowledge comunque per evitare loop infiniti
            // Il messaggio fallito sarà tracciato in email_send_log
            try {
                NotificationConfigProperties.EventMapping mapping = findFirstMapping(stream);
                if (mapping != null && mapping.isAutoAck()) {
                    acknowledgeMessage(stream, mapping, messageId);
                }
            } catch (Exception ackError) {
                logger.error("Error acknowledging failed message: {}", ackError.getMessage());
            }
        } finally {
            // Chiudi connessione se aperta in questo metodo
            if (connectionOpened && Base.hasConnection()) {
                Base.close();
                logger.debug("Closed ActiveJDBC connection for listener thread");
            }
        }
    }

    /**
     * Acknowledge un messaggio nello stream
     */
    private void acknowledgeMessage(String stream, NotificationConfigProperties.EventMapping mapping, RecordId messageId) {
        try {
            if (mapping != null) {
                redisTemplate.opsForStream().acknowledge(
                    stream,
                    mapping.getConsumerGroup(),
                    messageId
                );
                logger.debug("Acknowledged message: stream={}, messageId={}", stream, messageId);
            }
        } catch (Exception e) {
            logger.error("Error acknowledging message: stream={}, messageId={}, error={}",
                stream, messageId, e.getMessage());
        }
    }

     /**
     * Trova il primo mapping configurato per uno stream
     */
    private NotificationConfigProperties.EventMapping findFirstMapping(String streamName) {
        if (config.getEventMappings() == null) {
            return null;
        }

        return config.getEventMappings().stream()
            .filter(m -> m.getStream().equals(streamName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Trova il primo mapping configurato per uno stream ed event-type
     */
    private NotificationConfigProperties.EventMapping findMapping(String streamName, String eventType) {
        if (config.getEventMappings() == null) {
            return null;
        }

        return config.getEventMappings().stream()
            .filter(m -> m.getStream().equals(streamName))
            .filter(m -> m.getEventType().equals(eventType))
            .findFirst()
            .orElse(null);
    }

    /**
     * Pulisce i valori JSON serializzati (rimuove virgolette extra)
     * Quando il backend usa ObjectRecord, i valori vengono serializzati come JSON strings
     * Es: "\"events:new_purchase_order\"" -> "events:new_purchase_order"
     */
    private Map<String, String> cleanJsonValues(Map<String, String> rawPayload) {
        Map<String, String> cleanedPayload = new HashMap<>();

        for (Map.Entry<String, String> entry : rawPayload.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value != null) {
                // Se il valore inizia e finisce con virgolette, le rimuove (è una stringa JSON serializzata)
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);

                    // IMPORTANTE: Fare unescape SOLO se il valore NON è un JSON object/array
                    // Se è un JSON object (inizia con { o [), gli escape devono rimanere
                    String trimmed = value.trim();
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        // È una stringa semplice, possiamo fare unescape
                        value = value.replace("\\\"", "\"");
                        value = value.replace("\\n", "\n");
                        value = value.replace("\\r", "\r");
                        value = value.replace("\\t", "\t");
                        value = value.replace("\\\\", "\\");
                    }
                    // Se è un JSON object/array, NON fare unescape - verrà parsato da Jackson
                }
            }

            cleanedPayload.put(key, value);
        }

        return cleanedPayload;
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down Valkey Stream Listener");

        // Cancella subscriptions
        for (Subscription subscription : subscriptions) {
            try {
                subscription.cancel();
            } catch (Exception e) {
                logger.error("Error cancelling subscription: {}", e.getMessage());
            }
        }

        // Ferma container
        if (listenerContainer != null) {
            listenerContainer.stop();
        }

        logger.info("Valkey Stream Listener shut down");
    }

}
