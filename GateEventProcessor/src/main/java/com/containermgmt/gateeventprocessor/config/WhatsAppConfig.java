package com.containermgmt.gateeventprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * WhatsApp send-message endpoint configuration (bernardini-os).
 * url   = full REST endpoint, e.g. https://bernardini-os.fiai.pro/api/whatsapp/send-message
 * token = Bearer token sent in the Authorization header
 * account = "account" field in the request body
 */
@Configuration
@ConfigurationProperties(prefix = "whatsapp")
@Getter
@Setter
public class WhatsAppConfig {

    private String url;
    private String imageUrl;
    private String token;
    private String account = "main";
    /** When true, no REST call is made: requests are only logged. */
    private boolean dryRun = true;
    /** entityType used when uploading temporary attachments to BERLink. */
    private String tempEntityType = "document";
    /** Validity (minutes) of the temporary attachments created for the WhatsApp links. */
    private int tempTimeoutMinutes = 60;
}
