package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.config.ActiveJDBCConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
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
 * Generic orchestrator that auto-discovers StreamProcessor beans
 * and sets up Valkey stream listeners for each one.
 */
@Component
@Slf4j
public class StreamListenerOrchestrator {

    private final List<StreamProcessor> processors;
    private final RedisConnectionFactory connectionFactory;
    private final RedisTemplate<String, String> redisTemplate;
    private final ActiveJDBCConfig activeJDBCConfig;
    private final int pollTimeoutSeconds;

    private final List<StreamMessageListenerContainer<String, MapRecord<String, String, String>>> containers = new ArrayList<>();
    private final List<Subscription> subscriptions = new ArrayList<>();

    public StreamListenerOrchestrator(List<StreamProcessor> processors,
                                      RedisConnectionFactory connectionFactory,
                                      RedisTemplate<String, String> redisTemplate,
                                      ActiveJDBCConfig activeJDBCConfig,
                                      @Value("${stream.poll-timeout-seconds:1}") int pollTimeoutSeconds) {
        this.processors = processors;
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.activeJDBCConfig = activeJDBCConfig;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    @PostConstruct
    public void init() {
        log.info("Discovered {} stream processor(s)", processors.size());

        for (StreamProcessor processor : processors) {
            String streamKey = processor.streamKey();
            String consumerGroup = processor.consumerGroup();

            log.info("Setting up listener: stream={}, group={}", streamKey, consumerGroup);

            createConsumerGroup(streamKey, consumerGroup);
            startListenerContainer(processor);
        }
    }

    private void createConsumerGroup(String streamKey, String consumerGroup) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
            log.info("Created consumer group: stream={}, group={}", streamKey, consumerGroup);
        } catch (Exception e) {
            log.debug("Consumer group already exists: stream={}, group={}", streamKey, consumerGroup);
        }
    }

    private void startListenerContainer(StreamProcessor processor) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(pollTimeoutSeconds))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        String consumerName = getConsumerName();

        StreamOffset<String> offset = StreamOffset.create(processor.streamKey(), ReadOffset.lastConsumed());
        Consumer consumer = Consumer.from(processor.consumerGroup(), consumerName);

        StreamListener<String, MapRecord<String, String, String>> listener = message -> onMessage(message, processor);

        Subscription subscription = container.receive(consumer, offset, listener);

        containers.add(container);
        subscriptions.add(subscription);

        container.start();
        log.info("Stream listener started: stream={}, group={}, consumer={}",
                processor.streamKey(), processor.consumerGroup(), consumerName);
    }

    private void onMessage(MapRecord<String, String, String> message, StreamProcessor processor) {
        RecordId messageId = message.getId();
        log.debug("Received message: stream={}, messageId={}", processor.streamKey(), messageId);

        boolean connectionOpened = false;
        try {
            if (!Base.hasConnection()) {
                Base.open(activeJDBCConfig.getDriverClassName(),
                        activeJDBCConfig.getDbUrl(),
                        activeJDBCConfig.getDbUsername(),
                        activeJDBCConfig.getDbPassword());
                connectionOpened = true;
            }

            Map<String, String> fields = cleanJsonValues(message.getValue());
            processor.process(fields);

            redisTemplate.opsForStream().acknowledge(processor.streamKey(), processor.consumerGroup(), messageId);
            log.debug("Acknowledged message: stream={}, messageId={}", processor.streamKey(), messageId);

        } catch (Exception e) {
            log.error("Error processing message: stream={}, messageId={}, error={}",
                    processor.streamKey(), messageId, e.getMessage(), e);
            // Message stays in PEL for inspection via XPENDING / manual reprocessing
        } finally {
            if (connectionOpened && Base.hasConnection()) {
                Base.close();
            }
        }
    }

    private Map<String, String> cleanJsonValues(Map<String, String> rawPayload) {
        Map<String, String> cleaned = new HashMap<>();

        for (Map.Entry<String, String> entry : rawPayload.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value != null) {
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);

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

            cleaned.put(key, value);
        }

        return cleaned;
    }

    private String getConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("Could not get hostname, using default consumer name");
            return "tfp-event-ingester-consumer-" + System.currentTimeMillis();
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down StreamListenerOrchestrator");

        for (Subscription subscription : subscriptions) {
            try {
                subscription.cancel();
            } catch (Exception e) {
                log.error("Error cancelling subscription: {}", e.getMessage());
            }
        }

        for (StreamMessageListenerContainer<String, MapRecord<String, String, String>> container : containers) {
            try {
                container.stop();
            } catch (Exception e) {
                log.error("Error stopping listener container: {}", e.getMessage());
            }
        }

        log.info("StreamListenerOrchestrator shut down");
    }

}
