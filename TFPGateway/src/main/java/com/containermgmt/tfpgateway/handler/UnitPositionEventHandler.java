package com.containermgmt.tfpgateway.handler;

import com.containermgmt.tfpgateway.dto.EventMessage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Handler for BERNARDINI_ASSET_DAMAGES events.
 * Placeholder — specific business logic to be implemented later.
 */
@Component
@Order(1)
@Slf4j
public class UnitPositionEventHandler implements EventTypeHandler {

    private static final String SUPPORTED_TYPE = "BERNARDINI_UNIT_POSITIONS_MESSAGE";

    @Override
    public Set<String> supportedEventTypes() {
        return Set.of(SUPPORTED_TYPE);
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED_TYPE.equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(EventMessage eventMessage) {
        log.trace(" -- Handling {} event: messageId={}", SUPPORTED_TYPE, eventMessage.getMessageId());
        // TODO: implement asset-damage specific logic
    }
}
