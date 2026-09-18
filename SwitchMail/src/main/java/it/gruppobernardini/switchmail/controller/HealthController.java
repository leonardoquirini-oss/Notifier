package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.dao.HealthDao;
import it.gruppobernardini.switchmail.service.ProcessorValidationRunner;
import it.gruppobernardini.switchmail.util.CredentialCipher;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check secondo il contratto di piattaforma (`BERLink/prompt/HEALTH_CONTRACT.md`), cosi' che
 * FlowCenter possa interrogare SwitchMail con lo stesso job di probe degli altri servizi.
 *
 * <ul>
 *   <li>{@code /api/health/live} pubblico, sempre 200: deve rispondere anche a servizio non pronto.</li>
 *   <li>{@code /api/health/ready} con {@code X-API-Key}, 503 se una dipendenza e' DOWN.</li>
 *   <li>{@code /api/health} alias deprecato di {@code /ready}, per retrocompatibilita'.</li>
 * </ul>
 *
 * <p>Shape non wrappata (niente {@code {success, data}}), come i processori fratelli: il monitor
 * cerca prima {@code data.status} e poi {@code status} alla radice.
 *
 * <p>Il check {@code rules} e' specifico di questo servizio: una regola che punta a un processore
 * inesistente non ferma il boot (la UI che la ripara gira in questo processo), ma deve risultare
 * DOWN qui invece di restare in una riga di log.
 */
@RestController
@Slf4j
public class HealthController {

    /** Identificatore stabile nella flotta (kebab-case). */
    private static final String SERVICE_NAME = "switchmail";

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final HealthDao healthDao;
    private final ProcessorValidationRunner validationRunner;
    private final CredentialCipher cipher;
    private final String healthApiKey;
    private final String version;
    private final Clock clock;

    public HealthController(HealthDao healthDao, ProcessorValidationRunner validationRunner,
                            CredentialCipher cipher, @Value("${health.api-key:}") String healthApiKey,
                            ObjectProvider<BuildProperties> buildProperties, Clock clock) {
        this.healthDao = healthDao;
        this.validationRunner = validationRunner;
        this.cipher = cipher;
        this.healthApiKey = healthApiKey;
        // Assente quando si gira dai sorgenti senza il goal build-info (es. IDE): "dev" e' onesto.
        this.version = buildProperties.getIfAvailable() == null ? "dev" : buildProperties.getObject().getVersion();
        this.clock = clock;
    }

    @GetMapping("/api/health/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(envelope(UP));
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, Object>> ready(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (healthApiKey == null || healthApiKey.isBlank()) {
            log.error("health.api-key non configurata: /api/health/ready non utilizzabile");
            Map<String, Object> body = envelope(DOWN);
            body.put("error", "health.api-key non configurata");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        if (!healthApiKey.equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // checks: chiave = nome breve della dipendenza, valore = "UP"/"DOWN". Niente oggetti annidati:
        // il monitor di flotta legge stringhe.
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("database", pingDatabase() ? UP : DOWN);
        checks.put("rules", validationRunner.brokenRules().isEmpty() ? UP : DOWN);
        checks.put("credentials", cipher.isConfigured() ? UP : DOWN);

        boolean allUp = checks.values().stream().allMatch(UP::equals);

        Map<String, Object> body = envelope(allUp ? UP : DOWN);
        body.put("checks", checks);

        // Dettaglio fuori dai checks, che devono restare stringhe: serve a chi guarda il servizio,
        // non al monitor. Nessuna credenziale, solo id di regole e motivi.
        if (!validationRunner.brokenRules().isEmpty()) {
            body.put("details", Map.of("rules", validationRunner.brokenRules()));
        }

        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Alias storico, mantenuto per i probe gia' configurati. */
    @Deprecated
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> alias(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return ready(apiKey);
    }

    private Map<String, Object> envelope(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("service", SERVICE_NAME);
        body.put("version", version);
        // Stesso formato ISO-8601 UTC con millisecondi usato per tutti i timestamp del servizio.
        body.put("timestamp", TimestampUtil.now(clock));
        return body;
    }

    private boolean pingDatabase() {
        try {
            healthDao.ping();
            return true;
        } catch (Exception e) {
            log.warn("Health check del DB fallito: {}", e.getMessage());
            return false;
        }
    }
}
