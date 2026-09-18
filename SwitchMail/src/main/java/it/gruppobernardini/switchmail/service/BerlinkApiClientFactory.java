package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.BerlinkApiConfig;
import it.gruppobernardini.switchmail.model.ParsedMail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * L'unico punto dove si sceglie tra client reale e client di registrazione.
 *
 * <p>La scelta sta qui e non dentro i processori: il contesto consegna al processore il client gia'
 * deciso, quindi non esiste un percorso di codice in cui un dry-run finisce per chiamare BERLink.
 */
@Component
public class BerlinkApiClientFactory {

    private final RestTemplate rest;
    private final BerlinkApiConfig config;

    public BerlinkApiClientFactory(RestTemplate berlinkRestTemplate, BerlinkApiConfig config) {
        this.rest = berlinkRestTemplate;
        this.config = config;
    }

    public BerlinkApiClient live(ParsedMail mail, Long ruleId) {
        return new DefaultBerlinkApiClient(rest, config, idempotencyKey(mail, ruleId));
    }

    public RecordingBerlinkApiClient recording(ParsedMail mail, Long ruleId) {
        return new RecordingBerlinkApiClient(idempotencyKey(mail, ruleId));
    }

    /**
     * Identita' della coppia (mail, regola): stabile tra i tentativi, cosi' un retry che rifa' la
     * stessa scrittura porta la stessa chiave.
     */
    public static String idempotencyKey(ParsedMail mail, Long ruleId) {
        if (mail == null) {
            return null;
        }
        return "switchmail:" + mail.accountId() + ":" + mail.uidValidity() + ":" + mail.uid()
                + ":" + (ruleId == null ? "-" : ruleId);
    }
}
