package it.gruppobernardini.switchmail.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * La mail materializzata: tutto quello che serve a valutare le regole e a far girare un processore,
 * senza nessun handle IMAP aperto.
 *
 * <p>Non e' una jakarta.mail.Message di proposito. Tenere una Message significherebbe: connessione
 * IMAP aperta durante la chiamata HTTP a BERLink (Exchange la chiude), fetch MIME lazy a sorpresa,
 * e un server IMAP necessario in ogni unit test.
 *
 * @param bodyText           mai null; stringa vuota se la mail non ha davvero testo
 * @param bodyHtml           null se assente
 * @param extractionWarnings fallback di charset, troncamenti, parti non decodificabili: finiscono
 *                           in mail_processing_log.warnings e sono visibili in UI. Un charset
 *                           indovinato di nascosto e' l'archetipo del silent failure.
 */
public record ParsedMail(
        long accountId,
        String accountName,
        String folder,
        long uidValidity,
        long uid,
        String internetMessageId,
        String fromAddress,
        String fromDisplayName,
        List<String> toAddresses,
        String subject,
        Instant sentAt,
        Instant receivedAt,
        String bodyText,
        String bodyHtml,
        List<MailAttachment> attachments,
        int rawSizeBytes,
        List<String> extractionWarnings) {

    public ParsedMail {
        toAddresses = toAddresses == null ? List.of() : List.copyOf(toAddresses);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        extractionWarnings = extractionWarnings == null ? List.of() : List.copyOf(extractionWarnings);
        if (bodyText == null) {
            bodyText = "";
        }
    }

    /** Il From completo come lo vede l'operatore: "Mario Rossi" &lt;mario.rossi@ferrovie.it&gt;. */
    public String fromFull() {
        if (fromDisplayName == null || fromDisplayName.isBlank()) {
            return fromAddress == null ? "" : fromAddress;
        }
        return "\"" + fromDisplayName + "\" <" + (fromAddress == null ? "" : fromAddress) + ">";
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public List<String> attachmentNames() {
        List<String> names = new ArrayList<>(attachments.size());
        for (MailAttachment a : attachments) {
            names.add(a.fileName());
        }
        return names;
    }

    /** Primo allegato il cui nome matcha la regex (semantica substring, case-insensitive). */
    public Optional<MailAttachment> attachment(String fileNameRegex) {
        Pattern p = Pattern.compile(fileNameRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return attachments.stream()
                .filter(a -> a.fileName() != null && p.matcher(a.fileName()).find())
                .findFirst();
    }

    /** Tutti gli allegati che matchano: serve a distinguere "assente" da "ambiguo". */
    public List<MailAttachment> attachments(String fileNameRegex) {
        Pattern p = Pattern.compile(fileNameRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return attachments.stream()
                .filter(a -> a.fileName() != null && p.matcher(a.fileName()).find())
                .toList();
    }

    /** Identita' RFC 3501 della mail dentro la casella. */
    public String dedupKey() {
        return accountId + ":" + folder + ":" + uidValidity + ":" + uid;
    }
}
