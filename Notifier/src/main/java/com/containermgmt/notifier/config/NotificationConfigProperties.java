package com.containermgmt.notifier.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuration Properties for Notification Mappings
 *
 * Maps Valkey stream events to email templates.
 * Loaded from notifications.yml or application.yml
 */
@Data
@Validated
@ConfigurationProperties(prefix = "notifications")
public class NotificationConfigProperties {

    /**
     * List of event-to-template mappings
     */
    @NotEmpty(message = "At least one event mapping must be configured")
    private List<EventMapping> eventMappings;

    /**
     * Represents a single mapping from a Valkey stream event to an email template
     */
    @Data
    public static class EventMapping {

        /**
         * Valkey stream name (e.g., "purchase-orders")
         */
        @NotBlank(message = "Stream name is required")
        private String stream;

        /**
         * Event type field value to match (e.g., "PURCHASE_ORDER_CREATED")
         * This should match a field in the stream message
         */
        @NotBlank(message = "Event type is required")
        private String eventType;

        /**
         * Email template code from database (e.g., "PO_CREATED")
         */
        @NotBlank(message = "Template code is required")
        private String templateCode;

        /**
         * Consumer group name for this stream
         */
        @NotBlank(message = "Consumer group is required")
        private String consumerGroup;

        /**
         * Consumer name (defaults to hostname if not specified)
         */
        private String consumerName;

        /**
         * Field name in the event that contains the event type
         * Default: "event_type"
         */
        private String eventTypeField = "event_type";

        /**
         * Whether to auto-acknowledge messages after processing
         * Default: true (acknowledge after successful send)
         */
        private boolean autoAck = true;


        /**
         * Se true, l'email da inviare non e' alla lista associata al template 
         * ma all'indirizzo specificato nei parametri
         */
        private boolean singleMail = false;

    }

}
