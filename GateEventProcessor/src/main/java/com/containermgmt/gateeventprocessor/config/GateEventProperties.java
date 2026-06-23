package com.containermgmt.gateeventprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@ConfigurationProperties(prefix = "stream.unit-events")
@Getter
@Setter
public class GateEventProperties {

    /** Valkey stream key (queue name) consumed by the processor. */
    private String key;

    /** Consumer group name. */
    private String consumerGroup;

    /** Allowed values for payload "type" field. Events with other types are skipped (and acknowledged). */
    private List<String> allowedTypes = Collections.emptyList();

    /** Suppression window: same unit at same gate is not re-notified within this duration. */
    private Duration throttleWindow = Duration.ofMinutes(30);
}
