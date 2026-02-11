package com.containermgmt.tfpgateway.dto;

import com.containermgmt.tfpgateway.config.GatewayProperties;
import lombok.Data;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Form-backing DTO for the gateway configuration page.
 * All fields are strings/primitives suitable for Thymeleaf form binding.
 */
@Data
public class GatewayRuntimeConfig {

    // Artemis connection
    private String brokerUrl;
    private String artemisUser;
    private String artemisPassword;

    // Gateway
    private String addresses;          // comma-separated
    private String subscriberName;
    private String concurrency;

    // Retry
    private int retryAttempts;
    private long retryDelayMs;

    // Artemis reconnection
    private long retryInterval;
    private double retryIntervalMultiplier;
    private long maxRetryInterval;
    private int reconnectAttempts;
    private long clientFailureCheckPeriod;
    private long connectionTtl;
    private long recoveryInterval;

    // Stream mapping (textarea: ADDRESS=stream-key, one per line)
    private String streamMappingText;

    /**
     * Factory: builds a DTO pre-populated from current GatewayProperties.
     */
    public static GatewayRuntimeConfig fromProperties(GatewayProperties p) {
        GatewayRuntimeConfig c = new GatewayRuntimeConfig();

        c.setBrokerUrl(p.getBrokerUrl());
        c.setArtemisUser(p.getArtemisUser());
        c.setArtemisPassword(p.getArtemisPassword());

        c.setAddresses(String.join(", ", p.getAddresses()));
        c.setSubscriberName(p.getSubscriberName());
        c.setConcurrency(p.getConcurrency());

        c.setRetryAttempts(p.getRetryAttempts());
        c.setRetryDelayMs(p.getRetryDelayMs());

        GatewayProperties.ArtemisProps a = p.getArtemis();
        c.setRetryInterval(a.getRetryInterval());
        c.setRetryIntervalMultiplier(a.getRetryIntervalMultiplier());
        c.setMaxRetryInterval(a.getMaxRetryInterval());
        c.setReconnectAttempts(a.getReconnectAttempts());
        c.setClientFailureCheckPeriod(a.getClientFailureCheckPeriod());
        c.setConnectionTtl(a.getConnectionTtl());
        c.setRecoveryInterval(a.getRecoveryInterval());

        String mapping = p.getStreamMapping().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        c.setStreamMappingText(mapping);

        return c;
    }

    /**
     * Applies this DTO's values back onto the given GatewayProperties.
     */
    public void applyTo(GatewayProperties p) {
        p.setBrokerUrl(brokerUrl);
        p.setArtemisUser(artemisUser);
        p.setArtemisPassword(artemisPassword);

        p.getAddresses().clear();
        if (addresses != null && !addresses.isBlank()) {
            for (String addr : addresses.split(",")) {
                String trimmed = addr.trim();
                if (!trimmed.isEmpty()) {
                    p.getAddresses().add(trimmed);
                }
            }
        }

        p.setSubscriberName(subscriberName != null ? subscriberName : "");
        p.setConcurrency(concurrency);
        p.setRetryAttempts(retryAttempts);
        p.setRetryDelayMs(retryDelayMs);

        GatewayProperties.ArtemisProps a = p.getArtemis();
        a.setRetryInterval(retryInterval);
        a.setRetryIntervalMultiplier(retryIntervalMultiplier);
        a.setMaxRetryInterval(maxRetryInterval);
        a.setReconnectAttempts(reconnectAttempts);
        a.setClientFailureCheckPeriod(clientFailureCheckPeriod);
        a.setConnectionTtl(connectionTtl);
        a.setRecoveryInterval(recoveryInterval);

        // Parse stream mapping textarea
        Map<String, String> map = p.getStreamMapping();
        map.clear();
        if (streamMappingText != null && !streamMappingText.isBlank()) {
            for (String line : streamMappingText.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.contains("=")) continue;
                int idx = trimmed.indexOf('=');
                String key = trimmed.substring(0, idx).trim();
                String val = trimmed.substring(idx + 1).trim();
                if (!key.isEmpty() && !val.isEmpty()) {
                    map.put(key, val);
                }
            }
        }
    }
}
