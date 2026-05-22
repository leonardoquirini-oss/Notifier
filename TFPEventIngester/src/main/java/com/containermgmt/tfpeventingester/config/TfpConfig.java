package com.containermgmt.tfpeventingester.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configurazione per l'integrazione con il sistema TFP (Track Fleet Platform).
 * Gestisce credenziali e parametri di connessione per il lookup delle missioni.
 */
@Configuration
public class TfpConfig {

    @Value("${tfp.api.base-url:}")
    private String baseUrl;

    @Value("${tfp.api.timeout:30000}")
    private int timeout;

    @Value("${tfp.api.retry-attempts:3}")
    private int retryAttempts;

    @Value("${tfp.api.username:}")
    private String username;

    @Value("${tfp.api.password:}")
    private String password;

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getTimeout() {
        return timeout;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isEmpty()
            && username != null && !username.isEmpty()
            && password != null && !password.isEmpty();
    }

    @Bean(name = "tfpRestTemplate")
    public RestTemplate tfpRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
}
