package com.containermgmt.tfpeventprocessor.config;

import com.containermgmt.tfpeventprocessor.listener.EventListener;
import com.containermgmt.tfpeventprocessor.listener.EventListener.MessageRedeliveryException;

import jakarta.annotation.PostConstruct;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistrar;
import org.springframework.jms.config.JmsListenerConfigurer;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Apache Artemis JMS Configuration
 *
 * Configures JMS listener container factory and programmatically registers
 * a listener endpoint for each queue listed in event-processor.queues.
 */
@Configuration
@EnableJms
@Slf4j
public class ArtemisConfig implements JmsListenerConfigurer {

    @Value("${event-processor.concurrency:3-10}")
    private String concurrency;

    @Value("${event-processor.acknowledge-messages:false}")
    private boolean acknowledgeMessages;

    @Value("${event-processor.queues}")
    private List<String> queues;

    private final EventListener eventListener;
    private final ConnectionFactory connectionFactory;

    public ArtemisConfig(EventListener eventListener, ConnectionFactory connectionFactory) {
        this.eventListener = eventListener;
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    public void init() {
        log.info("Configured queues: {}", queues);
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory() {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(concurrency);
        factory.setSessionTransacted(false);
        factory.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        // Error handling — suppress MessageRedeliveryException (expected when acknowledge is disabled)
        factory.setErrorHandler(t -> {
            Throwable cause = t.getCause();
            if (cause instanceof MessageRedeliveryException) {
                log.debug("Message redelivery forced (acknowledge disabled)");
            } else {
                log.error("Error in JMS listener: {}", t.getMessage(), t);
            }
        });

        log.info("JMS Listener Container Factory configured with concurrency: {}, acknowledgeMessages: {}",
                concurrency, acknowledgeMessages);

        return factory;
    }

    @Override
    public void configureJmsListeners(JmsListenerEndpointRegistrar registrar) {
        DefaultJmsListenerContainerFactory factory = jmsListenerContainerFactory();

        for (String queue : queues) {
            String trimmed = queue.trim();
            log.info("Registering JMS listener for queue: {}", trimmed);

            SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("evt-listener-" + trimmed);
            endpoint.setDestination(trimmed);
            endpoint.setMessageListener(msg -> eventListener.onMessage(trimmed, msg));

            registrar.registerEndpoint(endpoint, factory);
        }
    }
}
