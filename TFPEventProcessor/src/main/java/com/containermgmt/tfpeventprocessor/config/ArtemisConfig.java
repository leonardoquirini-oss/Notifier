package com.containermgmt.tfpeventprocessor.config;

import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.SimpleMessageConverter;

import lombok.extern.slf4j.Slf4j;

/**
 * Apache Artemis JMS Configuration
 *
 * Configures JMS listener container factory with message converter
 * and error handling.
 */
@Configuration
@EnableJms
@Slf4j
public class ArtemisConfig {

    @Value("${event-processor.concurrency:3-10}")
    private String concurrency;

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory connectionFactory) {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(concurrency);
        factory.setSessionTransacted(true);
        factory.setMessageConverter(new SimpleMessageConverter());

        // Error handling
        factory.setErrorHandler(t -> {
            log.error("Error in JMS listener: {}", t.getMessage(), t);
        });

        log.info("JMS Listener Container Factory configured with concurrency: {}", concurrency);

        return factory;
    }

}
