package com.containermgmt.chatprocessor.listener;

import com.containermgmt.chatprocessor.dto.ChatEvent;
import com.containermgmt.chatprocessor.service.ChatLogService;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Listener per Valkey Stream chatbot-log-stream.
 * Consuma eventi chatbot:request e chatbot:response.
 */
@Component
public class ChatLogStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger logger = LoggerFactory.getLogger(ChatLogStreamListener.class);

    @Autowired
    private ChatLogService chatLogService;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${chatprocessor.stream:chatbot-log-stream}")
    private String streamName;

    @Value("${chatprocessor.consumer-group:chatprocessor-group}")
    private String consumerGroup;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer;
    private Subscription subscription;

    @PostConstruct
    public void init() {
        logger.info("Initializing ChatLog Stream Listener for stream: {}", streamName);

        // Crea consumer group se non esiste
        createConsumerGroup();

        // Avvia listener container
        startListenerContainer();

        logger.info("ChatLog Stream Listener initialized successfully");
    }

    /**
     * Crea consumer group per lo stream
     */
    private void createConsumerGroup() {
        try {
            // Verifica se lo stream esiste
            if (!streamExists(streamName)) {
                logger.info("Stream {} does not exist, it will be created on first message", streamName);
            }

            // Crea consumer group se non esiste
            try {
                redisTemplate.opsForStream().createGroup(streamName, consumerGroup);
                logger.info("Created consumer group: stream={}, group={}", streamName, consumerGroup);
            } catch (Exception e) {
                // Group già esistente, ignora errore
                logger.debug("Consumer group already exists: stream={}, group={}", streamName, consumerGroup);
            }

        } catch (Exception e) {
            logger.error("Error creating consumer group: stream={}, group={}, error={}",
                streamName, consumerGroup, e.getMessage());
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
     * Avvia il listener container
     */
    private void startListenerContainer() {
        // Crea listener container
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(1))
                .build();

        listenerContainer = StreamMessageListenerContainer.create(connectionFactory, options);

        // Ottieni nome consumer (hostname o default)
        String consumerName = getConsumerName();

        // Registra subscription
        StreamOffset<String> offset = StreamOffset.create(streamName, ReadOffset.lastConsumed());
        Consumer consumer = Consumer.from(consumerGroup, consumerName);

        subscription = listenerContainer.receive(consumer, offset, this);

        logger.info("Registered subscription: stream={}, group={}, consumer={}",
            streamName, consumerGroup, consumerName);

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
            return "chatprocessor-consumer-" + System.currentTimeMillis();
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
            String eventType = payload.get(ChatEvent.EVENT_TYPE_FIELD);
            if (eventType == null) {
                logger.warn("Event type field '{}' not found in message payload, messageId={}",
                    ChatEvent.EVENT_TYPE_FIELD, messageId);
                acknowledgeMessage(messageId);
                return;
            }

            // Crea ChatEvent
            ChatEvent event = new ChatEvent(stream, messageId.getValue(), payload);
            event.setEventType(eventType);

            // Processa evento in base al tipo
            if (event.isRequest()) {
                logger.info("Processing chatbot:request, correlationId={}", event.getCorrelationId());
                chatLogService.processRequest(event);
            } else if (event.isResponse()) {
                logger.info("Processing chatbot:response, correlationId={}", event.getCorrelationId());
                chatLogService.processResponse(event);
            } else {
                logger.warn("Unknown event type: {}, messageId={}", eventType, messageId);
            }

            // Acknowledge messaggio
            acknowledgeMessage(messageId);

        } catch (Exception e) {
            logger.error("Error processing message: stream={}, messageId={}, error={}",
                stream, messageId, e.getMessage(), e);

            // Acknowledge comunque per evitare loop infiniti
            try {
                acknowledgeMessage(messageId);
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
    private void acknowledgeMessage(RecordId messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamName, consumerGroup, messageId);
            logger.debug("Acknowledged message: stream={}, messageId={}", streamName, messageId);
        } catch (Exception e) {
            logger.error("Error acknowledging message: stream={}, messageId={}, error={}",
                streamName, messageId, e.getMessage());
        }
    }

    /**
     * Pulisce i valori JSON serializzati (rimuove virgolette extra)
     */
    private Map<String, String> cleanJsonValues(Map<String, String> rawPayload) {
        Map<String, String> cleanedPayload = new HashMap<>();

        for (Map.Entry<String, String> entry : rawPayload.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value != null) {
                // Se il valore inizia e finisce con virgolette, le rimuove
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);

                    // Unescape solo se non è un JSON object/array
                    String trimmed = value.trim();
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        value = value.replace("\\\"", "\"");
                        value = value.replace("\\n", "\n");
                        value = value.replace("\\r", "\r");
                        value = value.replace("\\t", "\t");
                        value = value.replace("\\\\", "\\");
                    }
                }
            }

            cleanedPayload.put(key, value);
        }

        return cleanedPayload;
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down ChatLog Stream Listener");

        // Cancella subscription
        if (subscription != null) {
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

        logger.info("ChatLog Stream Listener shut down");
    }

}
