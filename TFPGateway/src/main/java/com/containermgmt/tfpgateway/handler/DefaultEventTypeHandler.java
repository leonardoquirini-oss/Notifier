package com.containermgmt.tfpgateway.handler;

import com.containermgmt.tfpgateway.dto.EventMessage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Catch-all handler for event types with no specific handler.
 * Always matches — must be the lowest-priority handler (@Order MAX_VALUE).
 */
@Component
@Order(Integer.MAX_VALUE)
@Slf4j
public class DefaultEventTypeHandler implements EventTypeHandler {

    @Override
    public boolean supports(String eventType) {
        return true;
    }

    @Override
    public void handle(EventMessage eventMessage) {
        log.trace(" -- No specific handler for eventType='{}' — raw event persisted, no further processing",
                eventMessage.getEventType());
    }
}
