package it.gruppobernardini.switchmail.model;

import java.time.Instant;

/**
 * Una riga di mail_rule. Immutabile: il matcher e i processori la ricevono come dato, non come
 * entita' modificabile.
 *
 * @param accountId null = la regola vale per tutti gli account
 * @param priority  ASC - numero piu' basso = valutata prima; il tie-break e' l'id
 */
public record RuleConfig(
        Long id,
        String name,
        String description,
        Long accountId,
        boolean enabled,
        int priority,
        boolean stopOnMatch,
        FieldCheck sender,
        FieldCheck subject,
        FieldCheck attachment,
        boolean requireAttachment,
        String processorId,
        String paramsJson,
        int maxAttempts,
        Instant createdAt,
        Instant updatedAt) {

    public RuleConfig {
        if (sender == null) {
            sender = FieldCheck.wildcard();
        }
        if (subject == null) {
            subject = FieldCheck.wildcard();
        }
        if (attachment == null) {
            attachment = FieldCheck.wildcard();
        }
        if (paramsJson == null || paramsJson.isBlank()) {
            paramsJson = "{}";
        }
    }

    public boolean appliesTo(long accountIdToCheck) {
        return accountId == null || accountId == accountIdToCheck;
    }

    public String label() {
        return "#" + id + " " + name;
    }
}
