package com.containermgmt.notifier.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration Properties per l'endpoint REST POST /email.
 *
 * Contiene la lista di API key valide accettate nell'header X-API-Key.
 * Caricata da application.yml (prefisso email.api).
 */
@Data
@ConfigurationProperties(prefix = "email.api")
public class EmailApiProperties {

    /**
     * Lista di API key valide per l'header X-API-Key.
     * Se vuota, l'endpoint POST /email è disabilitato (risponde 503).
     */
    private List<String> keys = new ArrayList<>();
}
