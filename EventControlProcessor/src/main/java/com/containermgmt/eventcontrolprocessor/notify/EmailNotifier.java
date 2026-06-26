package com.containermgmt.eventcontrolprocessor.notify;

import com.containermgmt.eventcontrolprocessor.config.NotifierConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends an email via the Notifier service (POST {notifier.base-url}/email, DirectEmailRequest contract).
 * Authenticates with X-API-Key. Fire-and-forget: failures are logged, never thrown.
 */
@Service
@Slf4j
public class EmailNotifier {

    private static final String SEND_PATH = "/email";

    private final NotifierConfig config;
    private final RestTemplate restTemplate = new RestTemplate();

    public EmailNotifier(NotifierConfig config) {
        this.config = config;
    }

    public void send(List<String> recipients, String subject, String body) {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            log.warn("notifier.base-url not configured; skipping email send");
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            log.warn("EmailNotifier: no recipients configured; skipping email send (subject={})", subject);
            return;
        }

        String url = config.getBaseUrl().replaceAll("/+$", "") + SEND_PATH;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", recipients);
        payload.put("subject", subject);
        payload.put("body", body);
        payload.put("is_html", false);
        if (config.getFromAddress() != null && !config.getFromAddress().isBlank()) {
            payload.put("from", config.getFromAddress());
        }
        if (config.getSenderName() != null && !config.getSenderName().isBlank()) {
            payload.put("sender_name", config.getSenderName());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.set("X-API-Key", config.getApiKey());
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            log.info("Email sent: to={}, subject={}, http={}", recipients, subject, resp.getStatusCode().value());
        } catch (Exception e) {
            log.error("Email send failed: url={}, to={}, subject={}, error={}",
                    url, recipients, subject, e.getMessage());
        }
    }
}
