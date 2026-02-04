package com.containermgmt.tfpgateway.handler;

import com.containermgmt.tfpgateway.dto.EventMessage;

import java.util.Collections;
import java.util.Set;

/**
 * Strategy interface for event type-specific processing.
 *
 * Implementations handle a specific event type (or a catch-all default).
 * Spring discovers them via component scanning; ordering is controlled by @Order.
 */
public interface EventTypeHandler {

    boolean supports(String eventType);

    void handle(EventMessage eventMessage);

    default Set<String> supportedEventTypes() {
        return Collections.emptySet();
    }
}
