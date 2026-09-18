package it.gruppobernardini.switchmail.dto;

import java.util.List;

/**
 * Input di /ruletest: dati a mano oppure un .eml caricato.
 *
 * <p>Il percorso .eml passa dal vero MailContentExtractor, quindi valida anche charset e allegati:
 * un file scaricato da /logs piu' questa pagina fanno un ciclo completo di sviluppo formati offline.
 */
public record RuleTestRequest(
        Long accountId,
        String from,
        String fromName,
        String subject,
        String body,
        List<String> attachmentNames,
        byte[] eml,
        boolean dryRun) {

    public RuleTestRequest {
        attachmentNames = attachmentNames == null ? List.of() : List.copyOf(attachmentNames);
    }

    public boolean hasEml() {
        return eml != null && eml.length > 0;
    }
}
