package com.containermgmt.tfpeventingester.service;

import com.containermgmt.tfpeventingester.config.TfpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Client per l'integrazione con TFP (Track Fleet Platform).
 * Gestisce autenticazione JWT, caching del token e retry su 401.
 */
@Component
@Slf4j
public class TfpClient {

    private static final String LOGIN_ENDPOINT = "/api/core/auth/";

    private final TfpConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private String currentToken;
    private LocalDateTime tokenExpiry;

    public TfpClient(TfpConfig config,
                     @Qualifier("tfpRestTemplate") RestTemplate restTemplate,
                     ObjectMapper objectMapper) {
        this.config = config;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    private synchronized String getValidToken() {
        if (!config.isConfigured()) {
            throw new TfpException("TFP API non configurata. Verificare le proprietà tfp.api.*");
        }
        if (currentToken == null || isTokenExpired()) {
            log.info("Token TFP scaduto o assente, effettuo login...");
            currentToken = login();
            tokenExpiry = extractExpiryFromToken(currentToken);
            log.info("Login TFP completato. Token valido fino a: {}", tokenExpiry);
        }
        return currentToken;
    }

    private synchronized void invalidateToken() {
        this.currentToken = null;
        this.tokenExpiry = null;
    }

    /**
     * Chiamata POST autenticata. Su 401 invalida il token e ritenta una volta.
     */
    public <T> T post(String endpoint, Object body, Class<T> responseType) {
        try {
            return doPost(endpoint, body, responseType);
        } catch (TfpException e) {
            if (e.isUnauthorized()) {
                log.warn("Token TFP rifiutato (401), retry dopo invalidazione");
                invalidateToken();
                return doPost(endpoint, body, responseType);
            }
            throw e;
        }
    }

    private <T> T doPost(String endpoint, Object body, Class<T> responseType) {
        try {
            String token = getValidToken();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);

            HttpEntity<?> entity = new HttpEntity<>(body, headers);
            String url = config.getBaseUrl() + endpoint;
            log.debug("Chiamata TFP POST {} - {}", url, body);

            ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, responseType);
            return response.getBody();
        } catch (HttpClientErrorException.Unauthorized e) {
            invalidateToken();
            throw new TfpException("Token non autorizzato", 401, e);
        } catch (HttpClientErrorException e) {
            throw new TfpException("Errore client TFP: " + e.getMessage(), e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            throw new TfpException("Errore server TFP: " + e.getMessage(), e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new TfpException("Errore di connessione a TFP: " + e.getMessage(), e);
        }
    }

    private String login() {
        String url = config.getBaseUrl() + LOGIN_ENDPOINT;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> credentials = new HashMap<>();
            credentials.put("username", config.getUsername());
            credentials.put("password", config.getPassword());

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(credentials, headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);

            if (response.getBody() == null || !response.getBody().has("token")) {
                throw new TfpException("Risposta login TFP non contiene il token");
            }
            log.info("Login TFP riuscito");
            return response.getBody().get("token").asText();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new TfpException("Login TFP fallito: " + e.getMessage(), e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new TfpException("Impossibile connettersi a TFP: " + e.getMessage(), e);
        }
    }

    private boolean isTokenExpired() {
        if (tokenExpiry == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(tokenExpiry.minusMinutes(5));
    }

    private LocalDateTime extractExpiryFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return LocalDateTime.now().plusHours(23);
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode payloadJson = objectMapper.readTree(payload);
            if (payloadJson.has("exp")) {
                long expTimestamp = payloadJson.get("exp").asLong();
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(expTimestamp), ZoneId.systemDefault());
            }
            return LocalDateTime.now().plusHours(23);
        } catch (Exception e) {
            log.warn("Errore parsing token JWT, uso scadenza default 23 ore: {}", e.getMessage());
            return LocalDateTime.now().plusHours(23);
        }
    }
}
