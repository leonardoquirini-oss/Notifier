package com.containermgmt.tfpeventingester.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configurazione per l'integrazione con TIRConnector (accesso DB TIR ElencoRichieste3).
 */
@Configuration
@ConfigurationProperties(prefix = "tirconnector.api")
@Getter
@Setter
public class TirConnectorConfig {

    private String baseUrl;
    private String key;
    private int timeout = 30000;

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Bean(name = "tirRestTemplate")
    public RestTemplate tirRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
}
