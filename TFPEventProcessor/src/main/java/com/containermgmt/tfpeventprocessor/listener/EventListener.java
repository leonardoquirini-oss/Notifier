package com.containermgmt.tfpeventprocessor.listener;

import com.containermgmt.tfpeventprocessor.dto.EventMessage;
import com.containermgmt.tfpeventprocessor.service.EventProcessorService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Event Listener
 *
 * Listens to Apache Artemis queues for incoming events.
 * Implements retry logic for failed processing attempts.
 */
@Component
@Slf4j
public class EventListener {

    private final EventProcessorService eventProcessorService;
    private final ObjectMapper objectMapper;

    @Value("${event-processor.retry-attempts:3}")
    private int maxRetries;

    @Value("${event-processor.retry-delay-ms:5000}")
    private long retryDelay;

    public EventListener(EventProcessorService eventProcessorService, ObjectMapper objectMapper) {
        this.eventProcessorService = eventProcessorService;
        this.objectMapper = objectMapper;
    }

    /**
     * Listens to the primary event queue.
     * Queue name is configured in application.yml
     */
    @JmsListener(
        destination = "${event-processor.queues.primary:events.queue}",
        containerFactory = "jmsListenerContainerFactory"
    )
    public void onPrimaryQueueMessage(String messageJson) {
        try {
            EventMessage eventMessage = objectMapper.readValue(messageJson, EventMessage.class);
            log.debug("Received message from primary queue: eventId={}", eventMessage.getEventId());
            processWithRetry(eventMessage);
        } catch (Exception e) {
            log.error("Failed to deserialize message: {}", e.getMessage());
            throw new RuntimeException("Failed to deserialize event message", e);
        }
    }

    /**
     * Listens to raw string messages (alternative endpoint).
     * Uncomment if needed for raw JSON processing.
     */
    // @JmsListener(
    //     destination = "${event-processor.queues.raw:events.queue.raw}",
    //     containerFactory = "jmsListenerContainerFactory"
    // )
    // public void onRawMessage(String messageJson) {
    //     log.debug("Received raw message from queue");
    //     processRawWithRetry(messageJson);
    // }

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
                log.warn("Processing attempt {} failed for event {}: {}",
                    attempts, eventMessage.getEventId(), e.getMessage());

                if (attempts < maxRetries) {
                    sleep(retryDelay);
                }
            }
        }

        if (!success) {
            log.error("Max retries ({}) reached for event: {}",
                maxRetries, eventMessage.getEventId());
            // Here you could send to a DLQ or alert system
            throw new RuntimeException(
                "Failed to process event after " + maxRetries + " attempts: " +
                eventMessage.getEventId(), lastException);
        }
    }

    /**
     * Processes a raw JSON message with retry logic.
     */
    private void processRawWithRetry(String messageJson) {
        int attempts = 0;
        boolean success = false;
        Exception lastException = null;

        while (attempts < maxRetries && !success) {
            try {
                eventProcessorService.processRawMessage(messageJson);
                success = true;
            } catch (Exception e) {
                attempts++;
                lastException = e;
                log.warn("Processing attempt {} failed for raw message: {}",
                    attempts, e.getMessage());

                if (attempts < maxRetries) {
                    sleep(retryDelay);
                }
            }
        }

        if (!success) {
            log.error("Max retries ({}) reached for raw message", maxRetries);
            throw new RuntimeException(
                "Failed to process raw message after " + maxRetries + " attempts",
                lastException);
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

}
