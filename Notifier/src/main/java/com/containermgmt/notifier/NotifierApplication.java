package com.containermgmt.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Email Notifier Service - Valkey Stream Listener
 *
 * Listens to Valkey streams for events and sends email notifications
 * based on configured template mappings.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class NotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }

}
