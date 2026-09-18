package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.config.BerlinkApiConfig;
import it.gruppobernardini.switchmail.processor.MailProcessingException;
import it.gruppobernardini.switchmail.processor.RetryableMailProcessingException;
import it.gruppobernardini.switchmail.processor.TerminalMailProcessingException;
import it.gruppobernardini.switchmail.service.BerlinkApiClient;
import it.gruppobernardini.switchmail.service.DefaultBerlinkApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * La classificazione HTTP e' la sede piu' autorevole delle tre: chi scrive un processore deve
 * ottenere la semantica giusta senza pensarci.
 */
class BerlinkApiClientClassificationTest {

    private static final String KEY = "switchmail:1:42:7:5";

    private RestTemplate rest;
    private MockRestServiceServer server;
    private BerlinkApiClient client;

    @BeforeEach
    void setUp() {
        BerlinkApiConfig config = new BerlinkApiConfig();
        config.setBaseUrl("http://backend:8080/");
        config.setApiKey("test-key");
        rest = config.berlinkRestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        client = new DefaultBerlinkApiClient(rest, config, KEY);
    }

    @Test
    @DisplayName("successo: header X-API-Key e X-Idempotency-Key presenti, corpo JSON deserializzato")
    void successoEHeader() {
        server.expect(requestTo("http://backend:8080/api/bookings"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-key"))
                .andExpect(header("X-Idempotency-Key", KEY))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"id\":99}}", MediaType.APPLICATION_JSON));

        BerlinkApiClient.BerlinkResponse response = client.post("/api/bookings", Map.of("treno", "4521"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.value("success")).isEqualTo(true);
        assertThat(response.data()).containsEntry("id", 99);
        server.verify();
    }

    @Test
    @DisplayName("4xx (non 429): terminale - ritentare una richiesta sbagliata spreca tentativi")
    void clientErrorTerminale() {
        server.expect(requestTo("http://backend:8080/api/bookings"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"treno inesistente\"}"));

        assertThatThrownBy(() -> client.post("/api/bookings", Map.of()))
                .isInstanceOf(TerminalMailProcessingException.class)
                .satisfies(e -> {
                    assertThat(((MailProcessingException) e).errorType()).isEqualTo("BERLINK_400");
                    assertThat(((MailProcessingException) e).retryable()).isFalse();
                })
                .hasMessageContaining("treno inesistente");
    }

    @Test
    void notFoundTerminale() {
        server.expect(requestTo("http://backend:8080/api/x")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.get("/api/x"))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((MailProcessingException) e).errorType())
                .isEqualTo("BERLINK_404");
    }

    @Test
    @DisplayName("429: ritentabile, non terminale")
    void tooManyRequestsRitentabile() {
        server.expect(requestTo("http://backend:8080/api/x"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.post("/api/x", Map.of()))
                .isInstanceOf(RetryableMailProcessingException.class)
                .extracting(e -> ((MailProcessingException) e).errorType())
                .isEqualTo("BERLINK_429");
    }

    @Test
    void serverErrorRitentabile() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT}) {
            server.reset();
            server.expect(requestTo("http://backend:8080/api/x")).andRespond(withStatus(status));

            assertThatThrownBy(() -> client.post("/api/x", Map.of()))
                    .as("status %s", status)
                    .isInstanceOf(RetryableMailProcessingException.class)
                    .extracting(e -> ((MailProcessingException) e).retryable())
                    .isEqualTo(true);
        }
    }

    @Test
    @DisplayName("timeout di lettura: ritentabile")
    void timeoutRitentabile() {
        server.expect(requestTo("http://backend:8080/api/x")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertThatThrownBy(() -> client.post("/api/x", Map.of()))
                .isInstanceOf(RetryableMailProcessingException.class)
                .extracting(e -> ((MailProcessingException) e).errorType())
                .isEqualTo("BERLINK_UNREACHABLE");
    }

    @Test
    @DisplayName("la GET non porta la chiave di idempotenza: non e' una scrittura")
    void getSenzaIdempotencyKey() {
        server.expect(requestTo("http://backend:8080/api/x"))
                .andExpect(header("X-API-Key", "test-key"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        BerlinkApiClient.BerlinkResponse response = client.get("/api/x");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json()).isEmpty();      // corpo non-oggetto: resta in rawBody
        assertThat(response.rawBody()).isEqualTo("[]");
        server.verify();
    }
}
