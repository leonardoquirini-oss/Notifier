package com.containermgmt.chatprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Chat Processor Application
 *
 * Consuma eventi dallo stream chatbot-log-stream
 * e persiste i dati nella tabella ai_chatbot_log_calls.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatProcessorApplication.class, args);
    }

}
