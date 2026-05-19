package com.containermgmt.gateeventprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the YAML top-level "gates" map. Each entry is a named gate definition.
 *
 * Example:
 * gates:
 *   gate-terni:
 *     latitude: 42.566593
 *     longitude: 12.603300
 *     radius: 500
 *     notify-group: officina-terni
 */
@ConfigurationProperties
@Getter
@Setter
public class GatesProperties {

    private Map<String, GateDefinition> gates = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class GateDefinition {
        /** Gate centre latitude (decimal degrees). */
        private double latitude;
        /** Gate centre longitude (decimal degrees). */
        private double longitude;
        /** Radius around the centre, in metres. Events outside this radius are ignored. */
        private double radius;
        /** Target notification group_code (see /api/notifications/send). */
        private String notifyGroup;
    }
}
