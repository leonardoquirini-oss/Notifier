package com.containermgmt.eventcontrolprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Notifier service configuration for sending emails via POST {base-url}/email.
 * The Notifier endpoint authenticates with the X-API-Key header.
 */
@Configuration
@ConfigurationProperties(prefix = "notifier")
@Getter
@Setter
public class NotifierConfig {

    private String baseUrl;
    private String apiKey;
    /** Default sender address used when a rule does not override it. */
    private String fromAddress;
    private String senderName;
}
