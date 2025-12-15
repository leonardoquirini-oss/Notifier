package com.containermgmt.tfpeventprocessor.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Generic Event Message DTO
 *
 * Represents an event received from Artemis queue.
 * Can be extended or replaced with specific DTOs for different event types.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventMessage {

    /**
     * Unique identifier for the event
     */
    private String eventId;

    /**
     * Type of event (e.g., ORDER_CREATED, USER_REGISTERED, etc.)
     */
    private String eventType;

    /**
     * Source system that generated the event
     */
    private String source;

    /**
     * Timestamp when the event was generated
     */
    private Instant timestamp;

    /**
     * Event payload as key-value pairs
     */
    private Map<String, Object> payload;

    /**
     * Optional metadata about the event
     */
    private Map<String, String> metadata;

    /**
     * Helper method to get a payload field as String
     */
    public String getPayloadField(String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Helper method to get a metadata field
     */
    public String getMetadataField(String key) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(key);
    }

}
