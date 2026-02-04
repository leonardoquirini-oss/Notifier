package com.containermgmt.tfpgateway.handler;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry / dispatcher that selects the right {@link EventTypeHandler}
 * for a given event type.
 *
 * At startup, builds a HashMap for O(1) lookup by event type.
 * Handlers with empty {@code supportedEventTypes()} are treated as catch-all
 * (the last one by @Order wins as default).
 */
@Component
@Slf4j
public class EventHandlerRegistry {

    private final List<EventTypeHandler> handlers;
    private final Map<String, EventTypeHandler> handlerMap = new HashMap<>();
    private EventTypeHandler defaultHandler;

    public EventHandlerRegistry(List<EventTypeHandler> handlers) {
        this.handlers = handlers;
    }

    @PostConstruct
    public void init() {
        for (EventTypeHandler h : handlers) {
            Set<String> types = h.supportedEventTypes();
            if (types.isEmpty()) {
                defaultHandler = h;
            } else {
                for (String type : types) {
                    String key = type.toLowerCase();
                    EventTypeHandler existing = handlerMap.put(key, h);
                    if (existing != null) {
                        log.warn("Duplicate handler for eventType '{}': {} replaced by {}",
                                key, existing.getClass().getSimpleName(), h.getClass().getSimpleName());
                    }
                }
            }
        }

        if (defaultHandler == null) {
            throw new IllegalStateException("No default (catch-all) EventTypeHandler found");
        }

        log.info("EventHandlerRegistry initialized: {} specific mappings, default={}",
                handlerMap.size(), defaultHandler.getClass().getSimpleName());
    }

    /**
     * Returns the handler for the given event type via O(1) map lookup,
     * falling back to the default catch-all handler.
     */
    public EventTypeHandler getHandler(String eventType) {
        if (eventType == null) {
            return defaultHandler;
        }
        return handlerMap.getOrDefault(eventType.toLowerCase(), defaultHandler);
    }
}
