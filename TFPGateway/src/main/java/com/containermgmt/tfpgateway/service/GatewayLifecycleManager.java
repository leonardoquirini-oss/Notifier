package com.containermgmt.tfpgateway.service;

import com.containermgmt.tfpgateway.config.GatewayProperties;
import com.containermgmt.tfpgateway.config.GatewayPropertiesInitializer;
import com.containermgmt.tfpgateway.dto.GatewayRuntimeConfig;
import com.containermgmt.tfpgateway.dto.GatewayStatusInfo;
import com.containermgmt.tfpgateway.listener.EventListener;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central service that manages JMS listener containers programmatically.
 * Replaces the old ArtemisConfig JmsListenerConfigurer approach so that
 * listeners can be stopped, started and reconfigured at runtime from
 * the gateway configuration page.
 */
@Service
@Slf4j
public class GatewayLifecycleManager {

    private final GatewayProperties props;
    private final EventListener eventListener;

    private final ConcurrentHashMap<String, DefaultMessageListenerContainer> containers = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile ActiveMQConnectionFactory connectionFactory;

    /**
     * GatewayPropertiesInitializer is injected to guarantee that its
     * @PostConstruct (which copies spring.artemis.* into GatewayProperties)
     * runs before this bean's @PostConstruct.
     */
    public GatewayLifecycleManager(GatewayProperties props,
                                   EventListener eventListener,
                                   GatewayPropertiesInitializer ignored) {
        this.props = props;
        this.eventListener = eventListener;
    }

    @PostConstruct
    public void init() {
        log.info("GatewayLifecycleManager initialising...");
        startAll();
    }

    @PreDestroy
    public void destroy() {
        log.info("GatewayLifecycleManager shutting down...");
        stopAll();
    }

    /**
     * Stops all listener containers gracefully and closes the connection factory.
     */
    public void stopAll() {
        lifecycleLock.lock();
        try {
            log.info("Stopping all listener containers ({} active)...", containers.size());
            for (Map.Entry<String, DefaultMessageListenerContainer> entry : containers.entrySet()) {
                stopContainer(entry.getKey(), entry.getValue());
            }
            containers.clear();
            closeConnectionFactory();
            log.info("All listener containers stopped.");
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Creates the connection factory (if needed) and starts a listener container
     * for each configured address.
     */
    public void startAll() {
        lifecycleLock.lock();
        try {
            if (!containers.isEmpty()) {
                log.warn("Containers already running - call stopAll() first.");
                return;
            }

            ensureConnectionFactory();

            List<String> addresses = props.getAddresses();
            String subscriberName = props.getSubscriberName();

            if (subscriberName == null || subscriberName.isBlank()) {
                log.info("Mode: DIRECT (anycast queues, no FQQN)");
            } else {
                log.info("Mode: MULTICAST durable shared subscription (subscriber-name: {})", subscriberName);
            }

            boolean fqqnMode = subscriberName != null && !subscriberName.isBlank();

            for (String address : addresses) {
                String addressName = address.trim();
                if (addressName.isEmpty()) continue;
                String destination = resolveDestination(addressName, subscriberName);
                startContainer(addressName, destination, fqqnMode);
            }

            log.info("Started {} listener container(s).", containers.size());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Stops all listeners, applies new configuration from the DTO to
     * GatewayProperties (and EventListener retry settings), then restarts.
     */
    public void reconfigure(GatewayRuntimeConfig config) {
        lifecycleLock.lock();
        try {
            log.info("Reconfiguring gateway...");
            stopAll();

            // Apply config to properties
            config.applyTo(props);

            // Update EventListener retry settings
            eventListener.setMaxRetries(props.getRetryAttempts());
            eventListener.setRetryDelay(props.getRetryDelayMs());

            startAll();
            log.info("Gateway reconfigured and restarted.");
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns current status (lock-free read from ConcurrentHashMap).
     */
    public GatewayStatusInfo getStatus() {
        List<GatewayStatusInfo.ListenerInfo> listeners = new ArrayList<>();

        for (Map.Entry<String, DefaultMessageListenerContainer> entry : containers.entrySet()) {
            DefaultMessageListenerContainer c = entry.getValue();
            listeners.add(GatewayStatusInfo.ListenerInfo.builder()
                    .address(entry.getKey())
                    .destination(c.getDestinationName())
                    .running(c.isRunning())
                    .activeConsumers(c.getActiveConsumerCount())
                    .build());
        }

        String state;
        if (listeners.isEmpty()) {
            state = "STOPPED";
        } else if (listeners.stream().allMatch(GatewayStatusInfo.ListenerInfo::isRunning)) {
            state = "RUNNING";
        } else if (listeners.stream().noneMatch(GatewayStatusInfo.ListenerInfo::isRunning)) {
            state = "STOPPED";
        } else {
            state = "PARTIAL";
        }

        return GatewayStatusInfo.builder()
                .state(state)
                .brokerUrl(props.getBrokerUrl())
                .listeners(listeners)
                .build();
    }

    // --- internal helpers ---

    private void ensureConnectionFactory() {
        if (connectionFactory != null) return;

        GatewayProperties.ArtemisProps a = props.getArtemis();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(props.getBrokerUrl());
        factory.setUser(props.getArtemisUser());
        factory.setPassword(props.getArtemisPassword());

        factory.setRetryInterval(a.getRetryInterval());
        factory.setRetryIntervalMultiplier(a.getRetryIntervalMultiplier());
        factory.setMaxRetryInterval(a.getMaxRetryInterval());
        factory.setReconnectAttempts(a.getReconnectAttempts());
        factory.setClientFailureCheckPeriod(a.getClientFailureCheckPeriod());
        factory.setConnectionTTL(a.getConnectionTtl());

        connectionFactory = factory;

        log.info("ActiveMQ ConnectionFactory created: brokerUrl={}, retryInterval={}ms, multiplier={}, maxInterval={}ms, attempts={}",
                props.getBrokerUrl(), a.getRetryInterval(), a.getRetryIntervalMultiplier(),
                a.getMaxRetryInterval(), a.getReconnectAttempts() == -1 ? "infinite" : a.getReconnectAttempts());
    }

    private void closeConnectionFactory() {
        if (connectionFactory != null) {
            try {
                connectionFactory.close();
            } catch (Exception e) {
                log.warn("Error closing connection factory: {}", e.getMessage());
            }
            connectionFactory = null;
        }
    }

    private void startContainer(String addressName, String destination, boolean multicast) {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(destination);
        container.setPubSubDomain(multicast);
        if (multicast) {
            container.setSubscriptionDurable(true);
            container.setSubscriptionShared(true);
            container.setSubscriptionName(props.getSubscriberName() + "." + addressName);
        }
        container.setConcurrency(props.getConcurrency());
        container.setSessionTransacted(true);
        container.setRecoveryInterval(props.getArtemis().getRecoveryInterval());
        container.setMessageListener((jakarta.jms.MessageListener) msg -> eventListener.onMessage(addressName, msg));

        container.setErrorHandler(t ->
                log.error("JMS listener error [{}]: {}", addressName, t.getMessage(), t));
        container.setExceptionListener(ex ->
                log.warn("JMS connection exception [{}]: {} - will attempt reconnect", addressName, ex.getMessage()));

        container.afterPropertiesSet();
        container.start();

        containers.put(addressName, container);
        log.info("Started listener for address: {} (destination: {})", addressName, destination);
    }

    private void stopContainer(String addressName, DefaultMessageListenerContainer container) {
        try {
            container.stop();
            container.shutdown();
            log.info("Stopped listener for address: {}", addressName);
        } catch (Exception e) {
            log.warn("Error stopping listener for address {}: {}", addressName, e.getMessage());
        }
    }

    private String resolveDestination(String addressName, String subscriberName) {
        // Durable subscription handles queue routing via clientId.subscriptionName
        // In DIRECT mode: plain address name (anycast queue)
        // In MULTICAST mode: address name (topic), JMS creates subscriber queue
        return addressName;
    }
}
