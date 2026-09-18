package it.gruppobernardini.switchmail.dto;

import java.time.Instant;
import java.util.List;

/**
 * Anteprima di una mail dalla sola ENVELOPE: niente body, niente fetch del contenuto, quindi nessun
 * rischio di toccare il flag \Seen. Finche' i formati sono ignoti, e' cosi' che l'operatore scopre
 * per cosa scrivere una regola.
 */
public record MailPreview(
        long uid,
        String from,
        String fromName,
        String subject,
        Instant sentAt,
        Instant receivedAt,
        boolean seen,
        List<String> attachmentNames) {
}
