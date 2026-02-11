package com.containermgmt.tfpgateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;

/**
 * Marker configuration for JMS support.
 *
 * Connection factory and listener containers are now managed
 * programmatically by {@link com.containermgmt.tfpgateway.service.GatewayLifecycleManager}.
 */
@Configuration
@EnableJms
public class ArtemisConfig {
}
