package com.containermgmt.tfpgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed binding for all gateway.* configuration.
 * Centralises every tunable knob so that GatewayLifecycleManager
 * (and the configuration UI) can read/write a single object.
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
@Data
public class GatewayProperties {

    /** Artemis multicast addresses to subscribe to. */
    private List<String> addresses = new ArrayList<>();

    /** Subscriber name (consumer group ID). Empty = direct/anycast mode. */
    private String subscriberName = "";

    /** JMS concurrency (min-max format, e.g. "3-10"). */
    private String concurrency = "3-10";

    /** Application-level retry attempts. */
    private int retryAttempts = 3;

    /** Delay between retry attempts (ms). */
    private long retryDelayMs = 5000;

    // --- Artemis connection (injected by GatewayPropertiesInitializer) ---

    /** Broker URL (tcp://...). */
    private String brokerUrl;

    /** Artemis username. */
    private String artemisUser;

    /** Artemis password. */
    private String artemisPassword;

    /** Artemis reconnection parameters. */
    private ArtemisProps artemis = new ArtemisProps();

    /** Artemis address name -> Valkey stream key. */
    private Map<String, String> streamMapping = new HashMap<>();

    @Data
    public static class ArtemisProps {
        private long retryInterval = 1000;
        private double retryIntervalMultiplier = 2.0;
        private long maxRetryInterval = 30000;
        private int reconnectAttempts = -1;
        private long clientFailureCheckPeriod = 5000;
        private long connectionTtl = 30000;
        private long recoveryInterval = 5000;
    }
}
