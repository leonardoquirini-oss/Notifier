package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.service.ProcessorValidationRunner;
import it.gruppobernardini.switchmail.util.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import it.gruppobernardini.switchmail.dao.HealthDao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness pubblica, readiness protetta.
 *
 * <p>La readiness include {@code checks.rules}: una regola che punta a un processore inesistente non
 * ferma il boot (la UI che la ripara gira in questo stesso processo), ma deve essere visibile a chi
 * guarda lo stato del servizio invece di restare solo in una riga di log.
 */
@RestController
@Slf4j
public class HealthController {

    private final HealthDao healthDao;
    private final ProcessorValidationRunner validationRunner;
    private final CredentialCipher cipher;
    private final String healthApiKey;

    public HealthController(HealthDao healthDao, ProcessorValidationRunner validationRunner,
                            CredentialCipher cipher, @Value("${health.api-key:}") String healthApiKey) {
        this.healthDao = healthDao;
        this.validationRunner = validationRunner;
        this.cipher = cipher;
        this.healthApiKey = healthApiKey;
    }

    @GetMapping("/api/health/live")
    public Map<String, Object> live() {
        return Map.of("status", "UP", "service", "switchmail");
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, Object>> ready(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        if (healthApiKey == null || healthApiKey.isBlank()) {
            log.error("health.api-key non configurata: /api/health/ready non utilizzabile");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "error", "health.api-key non configurata"));
        }
        if (!healthApiKey.equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "UNAUTHORIZED"));
        }

        Map<String, Object> checks = new LinkedHashMap<>();
        boolean up = true;

        try {
            healthDao.ping();
            checks.put("db", Map.of("status", "UP"));
        } catch (Exception e) {
            up = false;
            checks.put("db", Map.of("status", "DOWN", "error", String.valueOf(e.getMessage())));
        }

        Map<Long, String> broken = validationRunner.brokenRules();
        checks.put("rules", broken.isEmpty()
                ? Map.of("status", "UP")
                : Map.of("status", "DOWN", "broken", broken));
        if (!broken.isEmpty()) {
            up = false;
        }

        checks.put("credentials", cipher.isConfigured()
                ? Map.of("status", "UP")
                : Map.of("status", "DOWN", "error", "SWITCHMAIL_CREDS_KEY non configurata o non valida"));
        if (!cipher.isConfigured()) {
            up = false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("service", "switchmail");
        body.put("checks", checks);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
