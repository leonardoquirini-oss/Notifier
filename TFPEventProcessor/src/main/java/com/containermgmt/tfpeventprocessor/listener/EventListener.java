package com.containermgmt.tfpeventprocessor.listener;

import com.containermgmt.tfpeventprocessor.dto.EventMessage;
import com.containermgmt.tfpeventprocessor.service.EventProcessorService;

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
 * Processes incoming JMS messages from Apache Artemis multicast addresses.
 * Listeners are registered programmatically by ArtemisConfig;
 * the address name is passed as a parameter and used as event_type.
 *
 * Messages are consumed normally (JMS commit on success, rollback on failure).
 * Durable subscription queues ensure messages are retained when processor is offline.
 */
@Component
@Slf4j
public class EventListener {

    private final EventProcessorService eventProcessorService;

    @Value("${event-processor.retry-attempts:3}")
    private int maxRetries;

    @Value("${event-processor.retry-delay-ms:5000}")
    private long retryDelay;

    public EventListener(EventProcessorService eventProcessorService) {
        this.eventProcessorService = eventProcessorService;
    }

    /**
     * Processes a JMS message from the given multicast address.
     * Called by the programmatic listener registered in ArtemisConfig.
     *
     * @param addressName the Artemis multicast address the message came from (used as eventType)
     * @param message     the raw JMS message
     */
    public void onMessage(String addressName, Message message) {
        if (!(message instanceof jakarta.jms.TextMessage)) {
            log.debug("Ignoring non-text message from address {}: {}", addressName, message.getClass().getSimpleName());
            return;
        }
        try {
            String jmsMessageId = message.getJMSMessageID();
            String messageJson = ((jakarta.jms.TextMessage) message).getText();

            if (jmsMessageId == null || jmsMessageId.isBlank()) {
                jmsMessageId = generateMessageHash(addressName, messageJson);
                log.debug("No JMS MessageID - using generated hash: {}", jmsMessageId);
            }

            EventMessage eventMessage = EventMessage.builder()
                    .messageId(jmsMessageId)
                    .eventType(addressName)
                    .eventTime(Instant.now())
                    .rawPayload(messageJson)
                    .build();
            log.debug("Received event: address={}, type={}, eventTime={}", addressName, eventMessage.getEventType(), eventMessage.getEventTime());
            processWithRetry(eventMessage);

        } catch (Exception e) {
            log.error("Failed to process message from address {}: {}", addressName, e.getMessage());
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
     * Generates a deterministic SHA-256 hash from address name and payload.
     * Used as message_id fallback when JMS MessageID is absent,
     * ensuring the DB upsert ON CONFLICT (message_id) prevents duplicates.
     */
    private String generateMessageHash(String addressName, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(addressName.getBytes(StandardCharsets.UTF_8));
            digest.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return "SHA256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
