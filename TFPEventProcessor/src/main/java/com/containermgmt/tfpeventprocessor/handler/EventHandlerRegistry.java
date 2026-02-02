package com.containermgmt.tfpeventprocessor.handler;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registry / dispatcher that selects the right {@link EventTypeHandler}
 * for a given event type.
 *
 * Spring injects all handlers ordered by @Order; the first one whose
 * {@code supports()} returns true wins.
 */
@Component
@Slf4j
public class EventHandlerRegistry {

    private final List<EventTypeHandler> handlers;

    public EventHandlerRegistry(List<EventTypeHandler> handlers) {
        this.handlers = handlers;
    }

    @PostConstruct
    public void init() {
        log.info("Registered {} event-type handler(s):", handlers.size());
        for (EventTypeHandler h : handlers) {
            log.info("  - {}", h.getClass().getSimpleName());
        }
    }

    /**
     * Returns the first handler that supports the given event type.
     * The {@link DefaultEventTypeHandler} guarantees a match always exists.
     */
    public EventTypeHandler getHandler(String eventType) {
        return handlers.stream()
                .filter(h -> h.supports(eventType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No handler found for eventType=" + eventType));
    }
}
