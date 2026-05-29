package com.containermgmt.notifier.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurazione endpoint WhatsApp (bernardini-os).
 * imageUrl       = endpoint REST per inviare un allegato con estensione immagine (send-image)
 * fileUrl        = endpoint REST per inviare un allegato generico/documento (send-document)
 * token          = Bearer token inviato nell'header Authorization
 * account        = campo "account" nel body della richiesta
 * dryRun         = se true non viene effettuata alcuna chiamata REST: le richieste sono solo loggate
 *
 * Il link di download dell'allegato si costruisce da berlink.api (vedi BerlinkApiConfig),
 * non da questa configurazione.
 */
@Configuration
@ConfigurationProperties(prefix = "whatsapp")
@Getter
@Setter
public class WhatsAppConfig {

    private String imageUrl;
    private String fileUrl;
    private String token;
    private String account = "main";
    private boolean dryRun = true;
}
