package com.containermgmt.tfpgateway.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Copies spring.artemis.* values into GatewayProperties at startup.
 * These properties fall outside the "gateway" prefix so they are not
 * bound automatically by @ConfigurationProperties.
 */
@Component
@Slf4j
public class GatewayPropertiesInitializer {

    private final GatewayProperties props;

    @Value("${spring.artemis.broker-url:tcp://localhost:61616}")
    private String brokerUrl;

    @Value("${spring.artemis.user:admin}")
    private String artemisUser;

    @Value("${spring.artemis.password:admin}")
    private String artemisPassword;

    @Value("${spring.application.name:tfp-gateway}")
    private String appName;

    public GatewayPropertiesInitializer(GatewayProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        props.setBrokerUrl(brokerUrl);
        props.setArtemisUser(artemisUser);
        props.setArtemisPassword(artemisPassword);

        // Ensure FQQN mode: if subscriber-name is not explicitly configured,
        // default to the application name so that Artemis creates durable queues
        // that persist messages while the gateway is stopped.
        if (props.getSubscriberName() == null || props.getSubscriberName().isBlank()) {
            props.setSubscriberName(appName);
            log.info("subscriber-name was empty, defaulting to application name: {}", appName);
        }

        log.info("GatewayProperties initialised: brokerUrl={}, user={}, subscriber-name={} (FQQN mode)",
                brokerUrl, artemisUser, props.getSubscriberName());
    }
}
