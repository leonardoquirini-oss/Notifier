package com.containermgmt.tfpeventprocessor.listener;

import com.containermgmt.tfpeventprocessor.dto.EventMessage;
import com.containermgmt.tfpeventprocessor.service.EventProcessorService;

import jakarta.annotation.PostConstruct;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Event Listener
 *
 * Processes incoming JMS messages from Apache Artemis queues.
 * Listeners are registered programmatically by ArtemisConfig;
 * the queue name is passed as a parameter and used as event_type.
 */
@Component
@Slf4j
public class EventListener {

    private final EventProcessorService eventProcessorService;

    @Value("${event-processor.retry-attempts:3}")
    private int maxRetries;

    @Value("${event-processor.retry-delay-ms:5000}")
    private long retryDelay;

    @Value("${event-processor.acknowledge-messages:false}")
    private boolean acknowledgeMessages;

    public EventListener(EventProcessorService eventProcessorService) {
        this.eventProcessorService = eventProcessorService;
    }

    @PostConstruct
    public void init() {
        if (!acknowledgeMessages) {
            log.warn(">>> MESSAGE ACKNOWLEDGE IS DISABLED - messages will NOT be consumed and will be redelivered on restart. Do NOT use this setting in production! <<<");
        }
    }

    /**
     * Processes a JMS message from the given queue.
     * Called by the programmatic listener registered in ArtemisConfig.
     *
     * @param queueName the Artemis queue the message came from (used as eventType)
     * @param message   the raw JMS message
     */
    public void onMessage(String queueName, Message message) {
        if (!(message instanceof jakarta.jms.TextMessage)) {
            log.debug("Ignoring non-text message from queue {}: {}", queueName, message.getClass().getSimpleName());
            return;
        }
        try {
            String jmsMessageId = message.getJMSMessageID();
            String messageJson = ((jakarta.jms.TextMessage) message).getText();

            // Fallback: se il JMS MessageID è assente, genera un hash deterministico
            // per garantire che l'upsert ON CONFLICT (message_id) funzioni correttamente
            if (jmsMessageId == null || jmsMessageId.isBlank()) {
                jmsMessageId = generateMessageHash(queueName, messageJson);
                log.debug("No JMS MessageID - using generated hash: {}", jmsMessageId);
            }

            EventMessage eventMessage = EventMessage.builder()
                    .messageId(jmsMessageId)
                    .eventType(queueName)
                    .eventTime(Instant.now())
                    .rawPayload(messageJson)
                    .build();
            log.debug("Received event: queue={}, type={}, eventTime={}", queueName, eventMessage.getEventType(), eventMessage.getEventTime());
            processWithRetry(eventMessage);

            if (!acknowledgeMessages) {
                log.debug("Event persisted but acknowledge disabled - forcing redelivery via rollback");
                throw new MessageRedeliveryException();
            }
        } catch (MessageRedeliveryException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to process message from queue {}: {}", queueName, e.getMessage());
            throw new RuntimeException("Failed to process event message", e);
        }
    }

    /**
     * Processes an event with retry logic.
     */
    private void processWithRetry(EventMessage eventMessage) {
        int attempts = 0;
        boolean success = false;
        Exception lastException = null;

        while (attempts < maxRetries && !success) {
            try {
                eventProcessorService.processEvent(eventMessage);
                success = true;
            } catch (Exception e) {
                attempts++;
                lastException = e;
                log.warn("Processing attempt {} failed for event type={}: {}",
                    attempts, eventMessage.getEventType(), e.getMessage());

                if (attempts < maxRetries) {
                    sleep(retryDelay);
                }
            }
        }

        if (!success) {
            log.error("Max retries ({}) reached for event type={}",
                maxRetries, eventMessage.getEventType());
            throw new RuntimeException(
                "Failed to process event after " + maxRetries + " attempts: type=" +
                eventMessage.getEventType(), lastException);
        }
    }

    /**
     * Sleeps for the specified duration, handling interruptions.
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Retry sleep interrupted");
        }
    }

    /**
     * Generates a deterministic SHA-256 hash from queue name and payload.
     * Used as message_id fallback when JMS MessageID is absent,
     * ensuring the DB upsert ON CONFLICT (message_id) prevents duplicates.
     */
    private String generateMessageHash(String queueName, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(queueName.getBytes(StandardCharsets.UTF_8));
            digest.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return "SHA256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 è sempre disponibile in ogni JVM
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Thrown intentionally when acknowledge is disabled to prevent Spring
     * from auto-acknowledging the JMS message. Not a real error.
     */
    public static class MessageRedeliveryException extends RuntimeException {
        MessageRedeliveryException() {
            super("Acknowledge disabled - forcing message redelivery");
        }
    }
}
