package com.containermgmt.tfpgateway.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;

/**
 * Lightweight carrier for incoming events.
 *
 * Holds the queue name as event type, the reception timestamp,
 * and the full raw JSON string (stored as JSONB payload).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventMessage {

    /** JMS Message ID — used for upsert idempotency */
    private String messageId;

    /** Event type — the Artemis queue name the message was received from */
    private String eventType;

    /** Instant the message was received by the listener */
    private Instant eventTime;

    /** The full raw JSON string as received from the queue */
    private String rawPayload;
}
