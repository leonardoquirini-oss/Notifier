package com.containermgmt.tfpgateway.config;

import com.containermgmt.tfpgateway.listener.EventListener;

import jakarta.annotation.PostConstruct;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
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

    @Value("${gateway.concurrency:3-10}")
    private String concurrency;

    @Value("${gateway.addresses}")
    private List<String> addresses;

    @Value("${gateway.subscriber-name:}")
    private String subscriberName;

    // Artemis connection settings
    @Value("${spring.artemis.broker-url}")
    private String brokerUrl;

    @Value("${spring.artemis.user}")
    private String artemisUser;

    @Value("${spring.artemis.password}")
    private String artemisPassword;

    // Reconnection parameters
    @Value("${gateway.artemis.retry-interval:1000}")
    private long retryInterval;

    @Value("${gateway.artemis.retry-interval-multiplier:2.0}")
    private double retryIntervalMultiplier;

    @Value("${gateway.artemis.max-retry-interval:30000}")
    private long maxRetryInterval;

    @Value("${gateway.artemis.reconnect-attempts:-1}")
    private int reconnectAttempts;

    @Value("${gateway.artemis.client-failure-check-period:5000}")
    private long clientFailureCheckPeriod;

    @Value("${gateway.artemis.connection-ttl:30000}")
    private long connectionTTL;

    @Value("${gateway.artemis.recovery-interval:5000}")
    private long recoveryInterval;

    private final EventListener eventListener;

    public ArtemisConfig(EventListener eventListener) {
        this.eventListener = eventListener;
    }

    @PostConstruct
    public void init() {
        log.info("Addresses: {}", addresses);
        if (subscriberName == null || subscriberName.isBlank()) {
            log.info("Mode: DIRECT (anycast queues, no FQQN)");
        } else {
            log.info("Mode: FQQN multicast (subscriber-name: {})", subscriberName);
        }
        log.info("Reconnection: retryInterval={}ms, multiplier={}, maxInterval={}ms, attempts={}",
                retryInterval, retryIntervalMultiplier, maxRetryInterval,
                reconnectAttempts == -1 ? "infinite" : reconnectAttempts);
    }

    /**
     * Custom ActiveMQ Artemis ConnectionFactory with reconnection parameters.
     * Replaces Spring Boot auto-configured factory to enable automatic reconnection
     * when broker goes down.
     */
    @Bean
    public ActiveMQConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setUser(artemisUser);
        factory.setPassword(artemisPassword);

        // Reconnection parameters (exponential backoff)
        factory.setRetryInterval(retryInterval);
        factory.setRetryIntervalMultiplier(retryIntervalMultiplier);
        factory.setMaxRetryInterval(maxRetryInterval);
        factory.setReconnectAttempts(reconnectAttempts);

        // Connection health monitoring
        factory.setClientFailureCheckPeriod(clientFailureCheckPeriod);
        factory.setConnectionTTL(connectionTTL);

        log.info("ActiveMQ ConnectionFactory configured with reconnection support");

        return factory;
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ActiveMQConnectionFactory connectionFactory) {

        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(concurrency);
        factory.setSessionTransacted(true);

        // Recovery settings for JMS container
        factory.setRecoveryInterval(recoveryInterval);

        factory.setErrorHandler(t ->
            log.error("JMS listener error: {}", t.getMessage(), t)
        );

        // Exception listener for connection failures
        factory.setExceptionListener(ex ->
            log.warn("JMS connection exception: {} - will attempt reconnect", ex.getMessage())
        );

        log.info("JMS Listener Container Factory configured with concurrency: {}, recoveryInterval: {}ms",
                concurrency, recoveryInterval);

        return factory;
    }

    @Override
    public void configureJmsListeners(JmsListenerEndpointRegistrar registrar) {
        // Set default factory bean name - Spring will resolve it
        registrar.setContainerFactoryBeanName("jmsListenerContainerFactory");

        for (String address : addresses) {
            String addressName = address.trim();
            String destination = resolveDestination(addressName);

            log.info("Registering JMS listener for address: {} (destination: {})",
                    addressName, destination);

            SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("evt-listener-" + addressName);
            endpoint.setDestination(destination);
            endpoint.setMessageListener(msg -> eventListener.onMessage(addressName, msg));

            registrar.registerEndpoint(endpoint);
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
