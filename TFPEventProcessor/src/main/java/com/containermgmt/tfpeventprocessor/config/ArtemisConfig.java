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
 * Uses multicast addresses (topics) with durable subscriptions via FQQN
 * (Fully Qualified Queue Name) for Kafka-like consumer group semantics.
 *
 * Each processor identifies itself with a subscriber-name. Same name = resume
 * from last consumed offset. Different name = new independent subscription.
 *
 * FQQN pattern: ADDRESS::SUBSCRIBER_NAME.ADDRESS
 */
@Configuration
@EnableJms
@Slf4j
public class ArtemisConfig implements JmsListenerConfigurer {

    @Value("${event-processor.concurrency:3-10}")
    private String concurrency;

    @Value("${event-processor.addresses}")
    private List<String> addresses;

    @Value("${event-processor.subscriber-name:tfp-processor}")
    private String subscriberName;

    private final EventListener eventListener;
    private final ConnectionFactory connectionFactory;

    public ArtemisConfig(EventListener eventListener, ConnectionFactory connectionFactory) {
        this.eventListener = eventListener;
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    public void init() {
        if (subscriberName == null || subscriberName.isBlank()) {
            throw new IllegalArgumentException("event-processor.subscriber-name must not be empty");
        }
        log.info("Multicast addresses: {}", addresses);
        log.info("Subscriber name: {}", subscriberName);
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
            String fqqn = buildFQQN(addressName);

            log.info("Registering JMS listener for multicast address: {} via FQQN: {}",
                    addressName, fqqn);

            SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("evt-listener-" + addressName);
            endpoint.setDestination(fqqn);
            endpoint.setMessageListener(msg -> eventListener.onMessage(addressName, msg));

            registrar.registerEndpoint(endpoint, factory);
        }
    }

    /**
     * Builds the FQQN (Fully Qualified Queue Name) for a multicast address.
     * Format: ADDRESS::SUBSCRIBER_NAME.ADDRESS
     *
     * The Artemis JMS client recognizes :: as the FQQN separator and connects
     * to the specific subscription queue on the multicast address.
     */
    private String buildFQQN(String addressName) {
        return addressName + "::" + subscriberName + "." + addressName;
    }
}
