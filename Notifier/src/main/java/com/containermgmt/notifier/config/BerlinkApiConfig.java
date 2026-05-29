package com.containermgmt.notifier.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurazione dell'istanza BERLink raggiungibile dal servizio WhatsApp esterno.
 * baseUrl = URL pubblico di BERLink (NON l'hostname interno docker http://backend:8080).
 * apiKey  = API key accettata da quell'istanza, usata come ?token nei link di download allegati.
 *
 * Coerente con la sezione berlink.api degli altri processor: base-url e api-key devono
 * appartenere alla STESSA istanza, altrimenti il download del link allegato fallisce.
 */
@Configuration
@ConfigurationProperties(prefix = "berlink.api")
@Getter
@Setter
public class BerlinkApiConfig {

    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;
}
