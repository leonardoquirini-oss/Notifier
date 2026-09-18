package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.BerlinkApiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notifiche agli amministratori via BERLink ({@code POST /api/notifications/send}).
 *
 * <p>Fire-and-forget: un notify fallito viene loggato e ingoiato. Il motivo e' che le notifiche
 * segnalano guasti - se il canale di segnalazione rompesse la pipeline, un problema piccolo ne
 * diventerebbe uno grosso proprio nel momento peggiore.
 */
@Service
@Slf4j
public class AdminNotifier {

    private static final String SEND_PATH = "/api/notifications/send";

    private final RestTemplate rest;
    private final BerlinkApiConfig config;

    public AdminNotifier(RestTemplate berlinkRestTemplate, BerlinkApiConfig config) {
        this.rest = berlinkRestTemplate;
        this.config = config;
    }

    public void deadLetter(long logId, String subject, String errorType, String message) {
        send("SwitchMail: mail in dead-letter",
                "Oggetto: " + nz(subject) + "\nErrore: " + nz(errorType) + "\n" + nz(message)
                        + "\nRiprova o segna risolta dalla pagina /logs.",
                "/logs?id=" + logId);
    }

    public void pollFailure(String accountName, String error) {
        send("SwitchMail: poll fallito su " + accountName, nz(error), "/accounts");
    }

    public void uidValidityReset(String accountName, String folder, long oldValue, long newValue) {
        send("SwitchMail: UIDVALIDITY cambiata su " + accountName,
                "Cartella " + folder + ": " + oldValue + " -> " + newValue
                        + ". Il servizio ripartira' dalla cartella corrente evitando i doppioni gia' noti "
                        + "(confronto su Message-ID); le mail non riconosciute verranno rielaborate.",
                "/logs");
    }

    public void brokenRules(String detail) {
        send("SwitchMail: regole con processore mancante", detail, "/rules");
    }

    public void send(String title, String message, String link) {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            log.warn("berlink.api.base-url non configurato: notifica '{}' non inviata", title);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group_code", config.getNotificationGroupCode());
        body.put("notification_type", "SWITCHMAIL");
        body.put("title", title);
        body.put("message", message);
        if (link != null && !link.isBlank()) {
            body.put("link", link);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            rest.exchange(config.url(SEND_PATH), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Notifica admin inviata: {}", title);
        } catch (Exception e) {
            log.error("Notifica admin fallita ({}): {}", title, e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
