package com.containermgmt.tfpeventingester.service;

import com.containermgmt.tfpeventingester.config.TirConnectorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client minimale per TIRConnector: esegue query SQL su DB TIR via
 * POST /api/Query/execute e ritorna le righe come lista di Map.
 */
@Component
@Slf4j
public class TirConnectorClient {

    private static final String QUERY_ENDPOINT = "/api/Query/execute";

    private final TirConnectorConfig config;
    private final RestTemplate restTemplate;

    public TirConnectorClient(TirConnectorConfig config,
                              @Qualifier("tirRestTemplate") RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> executeQuery(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getKey() != null && !config.getKey().isBlank()) {
            headers.set("X-API-Key", config.getKey());
        }

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("query", query);

        String url = config.getBaseUrl() + QUERY_ENDPOINT;
        log.debug("TIRConnector query: {}", query);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(requestBody, headers), Map.class);

        Map<String, Object> body = response.getBody();
        if (body == null) {
            return List.of();
        }
        if (body.containsKey("error")) {
            throw new TfpException("TIRConnector error: " + body.get("error") + " - " + body.get("message"));
        }
        Object data = body.get("data");
        return data instanceof List ? (List<Map<String, Object>>) data : List.of();
    }
}
