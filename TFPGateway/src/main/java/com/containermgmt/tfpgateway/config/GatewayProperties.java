package com.containermgmt.tfpgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed binding for gateway.stream-mapping configuration.
 * Maps Artemis address names to Valkey stream keys.
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
@Data
public class GatewayProperties {

    /** Artemis address name -> Valkey stream key */
    private Map<String, String> streamMapping = new HashMap<>();
}
