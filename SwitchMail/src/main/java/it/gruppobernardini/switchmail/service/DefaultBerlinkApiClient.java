package it.gruppobernardini.switchmail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gruppobernardini.switchmail.config.BerlinkApiConfig;
import it.gruppobernardini.switchmail.processor.MailProcessingException;
import it.gruppobernardini.switchmail.processor.RetryableMailProcessingException;
import it.gruppobernardini.switchmail.processor.TerminalMailProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Il client reale, legato a una mail.
 *
 * <p><b>Classifica l'HTTP al posto dell'autore del processore</b> - e' la prima delle tre sedi di
 * classificazione, e la piu' importante, perche' la maggior parte dei processori fallira' solo qui:
 *
 * <ul>
 *   <li>timeout, IOException, 502, 503, 504, 429 -&gt; Retryable</li>
 *   <li>4xx tranne 429 -&gt; Terminal (la richiesta e' sbagliata: ritentarla e' sprecare tentativi)</li>
 *   <li>altri 5xx -&gt; Retryable</li>
 * </ul>
 *
 * <p>Manda anche {@code X-Idempotency-Key} derivato dall'identita' della mail. BERLink oggi non lo
 * onora; quando lo fara', la finestra "crash dopo la chiamata, prima della riga di log" si chiude
 * senza toccare niente qui. Resta un punto aperto lato BERLink, annotato in CLAUDE.md.
 */
@Slf4j
public class DefaultBerlinkApiClient implements BerlinkApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest;
    private final BerlinkApiConfig config;
    private final String idempotencyKey;

    public DefaultBerlinkApiClient(RestTemplate rest, BerlinkApiConfig config, String idempotencyKey) {
        this.rest = rest;
        this.config = config;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public BerlinkResponse post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    @Override
    public BerlinkResponse get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    private BerlinkResponse exchange(HttpMethod method, String path, Object body) {
        String url = config.url(path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null && method != HttpMethod.GET) {
            headers.set("X-Idempotency-Key", idempotencyKey);
        }
        // X-API-Key la aggiunge l'interceptor di BerlinkApiConfig.

        try {
            ResponseEntity<String> response =
                    rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
            return new BerlinkResponse(response.getStatusCode().value(), response.getBody(),
                    parse(response.getBody()));
        } catch (HttpStatusCodeException e) {
            throw classify(e, method, url);
        } catch (ResourceAccessException e) {
            // timeout di connessione o di lettura, host irraggiungibile: transitori per definizione
            throw new RetryableMailProcessingException("BERLINK_UNREACHABLE",
                    method + " " + url + " non raggiungibile: " + e.getMessage(), e);
        } catch (MailProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TerminalMailProcessingException("BERLINK_CALL_FAILED",
                    method + " " + url + " fallita: " + e.getMessage(), e);
        }
    }

    private MailProcessingException classify(HttpStatusCodeException e, HttpMethod method, String url) {
        HttpStatusCode status = e.getStatusCode();
        int code = status.value();
        String detail = method + " " + url + " -> HTTP " + code + " " + abbreviate(e.getResponseBodyAsString());

        if (code == 429) {
            return new RetryableMailProcessingException("BERLINK_429", "rate limit: " + detail, e);
        }
        if (status.is4xxClientError()) {
            return new TerminalMailProcessingException("BERLINK_" + code,
                    "richiesta rifiutata da BERLink: " + detail, e);
        }
        if (code == 502 || code == 503 || code == 504) {
            return new RetryableMailProcessingException("BERLINK_" + code, "BERLink non disponibile: " + detail, e);
        }
        return new RetryableMailProcessingException("BERLINK_" + code, "errore lato BERLink: " + detail, e);
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    /** Il corpo puo' non essere un oggetto JSON: in quel caso resta disponibile come rawBody. */
    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = MAPPER.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
