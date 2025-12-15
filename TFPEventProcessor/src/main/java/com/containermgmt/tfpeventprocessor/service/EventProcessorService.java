package com.containermgmt.tfpeventprocessor.service;

import com.containermgmt.tfpeventprocessor.config.ActiveJDBCConfig;
import com.containermgmt.tfpeventprocessor.dto.EventMessage;
import com.containermgmt.tfpeventprocessor.exception.EventProcessingException;
import com.containermgmt.tfpeventprocessor.model.RawEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Event Processor Service
 *
 * Handles the business logic for processing events received from Artemis.
 * Manages ActiveJDBC connections per-thread for concurrent processing.
 */
@Service
@Slf4j
public class EventProcessorService {

    private final ActiveJDBCConfig activeJDBCConfig;
    private final ObjectMapper objectMapper;

    public EventProcessorService(ActiveJDBCConfig activeJDBCConfig, ObjectMapper objectMapper) {
        this.activeJDBCConfig = activeJDBCConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes an event message and persists it to the database.
     *
     * @param eventMessage the event to process
     */
    public void processEvent(EventMessage eventMessage) {
        String eventId = eventMessage.getEventId();
        log.info("Processing event: id={}, type={}", eventId, eventMessage.getEventType());

        try {
            // Open connection for this thread
            activeJDBCConfig.openConnection();

            // Check for duplicate
            RawEvent existing = RawEvent.findByEventId(eventId);
            if (existing != null) {
                log.warn("Duplicate event detected, skipping: id={}", eventId);
                return;
            }

            // Create new event record
            RawEvent event = new RawEvent();
            event.set("event_id", eventId);
            event.set("event_type", eventMessage.getEventType());
            event.set("source", eventMessage.getSource());
            event.set("timestamp", eventMessage.getTimestamp() != null
                ? Timestamp.from(eventMessage.getTimestamp()) : null);
            event.set("payload", serializeToJson(eventMessage.getPayload()));
            event.set("metadata", serializeToJson(eventMessage.getMetadata()));
            event.set("processed_at", Timestamp.from(Instant.now()));

            // Save to database
            if (!event.saveIt()) {
                throw new EventProcessingException(
                    "Failed to save event: " + eventId + ", errors: " + event.errors());
            }

            log.info("Successfully processed event: id={}, type={}",
                eventId, eventMessage.getEventType());

        } catch (EventProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing event: id={}, error={}",
                eventId, e.getMessage(), e);
            throw new EventProcessingException("Failed to process event: " + eventId, e);
        } finally {
            // Close connection for this thread
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * Processes a raw JSON message string.
     *
     * @param json the raw JSON message
     */
    public void processRawMessage(String json) {
        try {
            EventMessage eventMessage = objectMapper.readValue(json, EventMessage.class);
            processEvent(eventMessage);
        } catch (Exception e) {
            log.error("Failed to parse raw message: {}", e.getMessage(), e);
            throw new EventProcessingException("Failed to parse event message", e);
        }
    }

    /**
     * Serializes an object to JSON string.
     */
    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return null;
        }
    }

}
