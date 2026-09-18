package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dao.HealthDao;
import it.gruppobernardini.switchmail.service.ProcessorValidationRunner;
import it.gruppobernardini.switchmail.util.CredentialCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Conformita' al contratto di piattaforma (`BERLink/prompt/HEALTH_CONTRACT.md`): e' quello che
 * permette a FlowCenter di interrogare SwitchMail con lo stesso probe degli altri servizi, quindi
 * la shape va verificata campo per campo e non "a occhio".
 */
class HealthControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T09:30:00Z"), ZoneOffset.UTC);

    private static CredentialCipher cipher(boolean configured) {
        SwitchMailProperties props = new SwitchMailProperties();
        if (configured) {
            props.getSecurity().setCredsKey(Base64.getEncoder().encodeToString(new byte[32]));
        }
        CredentialCipher c = new CredentialCipher(props);
        c.init();
        return c;
    }

    private static ProcessorValidationRunner runner(Map<Long, String> broken) {
        ProcessorValidationRunner runner = mock(ProcessorValidationRunner.class);
        when(runner.brokenRules()).thenReturn(broken);
        return runner;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> build(String version) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        if (version == null) {
            when(provider.getIfAvailable()).thenReturn(null);
        } else {
            Properties p = new Properties();
            p.setProperty("version", version);
            BuildProperties properties = new BuildProperties(p);
            when(provider.getIfAvailable()).thenReturn(properties);
            when(provider.getObject()).thenReturn(properties);
        }
        return provider;
    }

    private static HealthController controller(HealthDao dao, Map<Long, String> broken, boolean credsOk,
                                               String key, String version) {
        return new HealthController(dao, runner(broken), cipher(credsOk), key, build(version), CLOCK);
    }

    @Test
    @DisplayName("live: sempre 200, con status/service/version/timestamp e senza checks")
    void liveSecondoContratto() {
        ResponseEntity<Map<String, Object>> response =
                controller(mock(HealthDao.class), Map.of(), true, "vera", "1.0.0").live();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("service", "switchmail")
                .containsEntry("version", "1.0.0")
                .containsEntry("timestamp", "2026-09-18T09:30:00.000Z")
                .doesNotContainKey("checks");
    }

    @Test
    @DisplayName("ready: i checks sono stringhe UP/DOWN, non oggetti annidati")
    void readySecondoContratto() {
        ResponseEntity<Map<String, Object>> response =
                controller(mock(HealthDao.class), Map.of(), true, "vera", "1.0.0").ready("vera");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody().get("checks")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) response.getBody().get("checks");
        assertThat(checks).containsOnlyKeys("database", "rules", "credentials");
        assertThat(checks.values()).allSatisfy(v -> assertThat(v).isInstanceOf(String.class).isEqualTo("UP"));
    }

    @Test
    @DisplayName("senza chiave valida: 401, come chiede il contratto")
    void chiaveSbagliata() {
        HealthController c = controller(mock(HealthDao.class), Map.of(), true, "vera", "1.0.0");

        assertThat(c.ready("falsa").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(c.ready(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("chiave non configurata: 503 che lo dice, non un boot fallito")
    void chiaveAssente() {
        ResponseEntity<Map<String, Object>> response =
                controller(mock(HealthDao.class), Map.of(), true, "", "1.0.0").ready(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN");
        assertThat(String.valueOf(response.getBody().get("error"))).contains("health.api-key");
    }

    @Test
    @DisplayName("una dipendenza DOWN porta l'intero readiness a DOWN con 503")
    void dipendenzeDown() {
        HealthDao failing = mock(HealthDao.class);
        doThrow(new IllegalStateException("database is locked")).when(failing).ping();

        ResponseEntity<Map<String, Object>> dbDown =
                controller(failing, Map.of(), true, "vera", "1.0.0").ready("vera");
        assertThat(dbDown.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(dbDown.getBody()).containsEntry("status", "DOWN");
        assertThat(dbDown.getBody().get("checks").toString()).contains("database=DOWN");

        ResponseEntity<Map<String, Object>> credsDown =
                controller(mock(HealthDao.class), Map.of(), false, "vera", "1.0.0").ready("vera");
        assertThat(credsDown.getBody().get("checks").toString()).contains("credentials=DOWN");
    }

    @Test
    @DisplayName("regole rotte: check DOWN e il dettaglio fuori dai checks, che restano stringhe")
    void regoleRotte() {
        ResponseEntity<Map<String, Object>> response =
                controller(mock(HealthDao.class), Map.of(7L, "processore sconosciuto 'x'"), true, "vera", "1.0.0")
                        .ready("vera");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("checks").toString()).contains("rules=DOWN");
        assertThat(response.getBody().get("details").toString()).contains("processore sconosciuto");
    }

    @Test
    @DisplayName("/api/health resta un alias di /ready")
    void alias() {
        HealthController c = controller(mock(HealthDao.class), Map.of(), true, "vera", "1.0.0");

        assertThat(c.alias("vera").getBody()).isEqualTo(c.ready("vera").getBody());
        assertThat(c.alias("falsa").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("senza build-info la versione e' 'dev', non una stringa vuota o null")
    void versioneSenzaBuildInfo() {
        ResponseEntity<Map<String, Object>> response =
                controller(mock(HealthDao.class), Map.of(), true, "vera", null).live();

        assertThat(response.getBody()).containsEntry("version", "dev");
    }
}
