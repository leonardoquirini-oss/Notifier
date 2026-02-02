package com.containermgmt.tfpeventprocessor.handler;

import com.containermgmt.tfpeventprocessor.dto.EventMessage;

/**
 * Strategy interface for event type-specific processing.
 *
 * Implementations handle a specific event type (or a catch-all default).
 * Spring discovers them via component scanning; ordering is controlled by @Order.
 */
public interface EventTypeHandler {

    boolean supports(String eventType);

    void handle(EventMessage eventMessage);
}
