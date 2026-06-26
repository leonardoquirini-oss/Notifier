package com.containermgmt.eventcontrolprocessor.notify;

import com.containermgmt.eventcontrolprocessor.config.BerlinkApiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin client for BERLink POST /api/notifications/send.
 * Reuses berlink.api.base-url + berlink.api.api-key (X-API-Key interceptor on berlinkRestTemplate).
 */
@Service
@Slf4j
public class NotificationClient {

    private static final String SEND_PATH = "/api/notifications/send";

    private final RestTemplate restTemplate;
    private final BerlinkApiConfig config;

    public NotificationClient(RestTemplate berlinkRestTemplate, BerlinkApiConfig config) {
        this.restTemplate = berlinkRestTemplate;
        this.config = config;
    }

    /** Fire-and-forget send. Logs and swallows exceptions: a failed notify must not crash the engine. */
    public void send(String groupCode, String notificationType, String title, String message, String link) {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            log.warn("berlink.api.base-url not configured; skipping send");
            return;
        }

        String url = config.getBaseUrl().replaceAll("/+$", "") + SEND_PATH;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group_code", groupCode);
        body.put("notification_type", notificationType);
        body.put("title", title);
        body.put("message", message);
        if (link != null && !link.isBlank()) {
            body.put("link", link);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // X-API-Key is added by berlinkRestTemplate interceptor (see BerlinkApiConfig).

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            log.info("Notification sent: group_code={}, type={}, link={}, http={}, body={}",
                    groupCode, notificationType, link, resp.getStatusCode().value(), resp.getBody());
        } catch (Exception e) {
            log.error("Notification send failed: url={}, group_code={}, type={}, error={}",
                    url, groupCode, notificationType, e.getMessage());
        }
    }
}
