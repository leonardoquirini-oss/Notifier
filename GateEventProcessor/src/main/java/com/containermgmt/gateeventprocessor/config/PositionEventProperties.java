package com.containermgmt.gateeventprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds stream.position-events.* — the Valkey stream carrying vehicle Position events.
 * Same shape (key + consumer-group) as stream.unit-events, but a different stream.
 */
@ConfigurationProperties(prefix = "stream.position-events")
@Getter
@Setter
public class PositionEventProperties {

    /** Valkey stream key (queue name) consumed by the processor. */
    private String key;

    /** Consumer group name. */
    private String consumerGroup;
}
