package com.containermgmt.tfpgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TFP Gateway Application
 *
 * Consumes events from Apache Artemis queues and persists them to PostgreSQL
 * using ActiveJDBC ORM.
 */
@SpringBootApplication
public class TfpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TfpGatewayApplication.class, args);
    }

}
