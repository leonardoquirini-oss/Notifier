package com.containermgmt.tfpeventprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TFP Event Processor Application
 *
 * Consumes events from Apache Artemis queues and persists them to PostgreSQL
 * using ActiveJDBC ORM.
 */
@SpringBootApplication
public class TfpEventProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TfpEventProcessorApplication.class, args);
    }

}
