package com.containermgmt.tfpeventprocessor.config;

import com.containermgmt.tfpeventprocessor.listener.EventListener;

import jakarta.annotation.PostConstruct;
import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.annotation.JmsListenerConfigurer;
import org.springframework.jms.config.JmsListenerEndpointRegistrar;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Apache Artemis JMS Configuration
 *
 * Supports two modes based on subscriber-name configuration:
 *
 * 1. FQQN multicast mode (subscriber-name set):
 *    Uses FQQN (ADDRESS::SUBSCRIBER_NAME.ADDRESS) for Kafka-like consumer
 *    group semantics with durable subscriptions on multicast addresses.
 *
 * 2. Direct/anycast mode (subscriber-name empty or absent):
 *    Connects directly to the queue by address name. Used for external
 *    Artemis brokers with standard anycast queues.
 */
@Configuration
@EnableJms
@Slf4j
public class ArtemisConfig implements JmsListenerConfigurer {

    @Value("${event-processor.concurrency:3-10}")
    private String concurrency;

    @Value("${event-processor.addresses}")
    private List<String> addresses;

    @Value("${event-processor.subscriber-name:}")
    private String subscriberName;

    private final EventListener eventListener;
    private final ConnectionFactory connectionFactory;

    public ArtemisConfig(EventListener eventListener, ConnectionFactory connectionFactory) {
        this.eventListener = eventListener;
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    public void init() {
        log.info("Addresses: {}", addresses);
        if (subscriberName == null || subscriberName.isBlank()) {
            log.info("Mode: DIRECT (anycast queues, no FQQN)");
        } else {
            log.info("Mode: FQQN multicast (subscriber-name: {})", subscriberName);
        }
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(concurrency);
        factory.setSessionTransacted(true);
        factory.setErrorHandler(t ->
            log.error("Error in JMS listener: {}", t.getMessage(), t)
        );

        log.info("JMS Listener Container Factory configured with concurrency: {}", concurrency);

        return factory;
    }

    @Override
    public void configureJmsListeners(JmsListenerEndpointRegistrar registrar) {
        DefaultJmsListenerContainerFactory factory = jmsListenerContainerFactory();

        for (String address : addresses) {
            String addressName = address.trim();
            String destination = resolveDestination(addressName);

            log.info("Registering JMS listener for address: {} (destination: {})",
                    addressName, destination);

            SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("evt-listener-" + addressName);
            endpoint.setDestination(destination);
            endpoint.setMessageListener(msg -> eventListener.onMessage(addressName, msg));

            registrar.registerEndpoint(endpoint, factory);
        }
    }

    /**
     * Resolves the JMS destination for a given address.
     *
     * If subscriber-name is set: returns FQQN (ADDRESS::SUBSCRIBER_NAME.ADDRESS)
     * for multicast durable subscriptions.
     *
     * If subscriber-name is empty: returns the address name directly
     * for anycast/direct queue consumption.
     */
    private String resolveDestination(String addressName) {
        if (subscriberName == null || subscriberName.isBlank()) {
            return addressName;
        }
        return addressName + "::" + subscriberName + "." + addressName;
    }
}
