package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.model.MailAttachment;
import it.gruppobernardini.switchmail.service.BerlinkApiClient;
import it.gruppobernardini.switchmail.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Base di ogni sub-processore: wrapper di classificazione + helper che danno al percorso rumoroso
 * la grafia piu' corta.
 *
 * <p>{@link #process} e' <b>final</b>: il try/catch che classifica le eccezioni non e' aggirabile.
 * Le sottoclassi scrivono {@link #handle}.
 *
 * <p>Gli helper esistono per chiudere il silenzio a livello di parser - il classico
 * {@code if (m.find()) {...}} senza else. Qui la via breve (requireGroup, parseDate) e' anche
 * quella che fallisce a voce alta.
 */
@Slf4j
public abstract class AbstractMailSubProcessor implements MailSubProcessor {

    @Override
    public final ProcessingOutcome process(MailContext ctx) {
        long t0 = System.currentTimeMillis();
        try {
            ProcessingOutcome out = handle(ctx);
            if (out == null) {
                throw new TerminalMailProcessingException("NULL_OUTCOME",
                        id() + " ha restituito null invece di un ProcessingOutcome", null);
            }
            return out;
        } catch (MailProcessingException e) {
            throw e;                                  // gia' classificata dal processore o dal client
        } catch (RuntimeException e) {
            // Default "ignoto => terminale": una NPE in un parser non guarisce al terzo tentativo,
            // ma farebbe partire tre chiamate a BERLink e tre notifiche. Niente va perso: il MIME
            // grezzo e' archiviato e /logs ha il bottone Riprova.
            throw new TerminalMailProcessingException("UNEXPECTED",
                    id() + ": " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        } finally {
            log.debug("{} su {} in {} ms", id(), ctx.mail().dedupKey(), System.currentTimeMillis() - t0);
        }
    }

    protected abstract ProcessingOutcome handle(MailContext ctx);

    /** L'unico accesso alla rete. In dry-run e' gia' il client che non manda nulla. */
    protected BerlinkApiClient api(MailContext ctx) {
        if (ctx.api() == null) {
            throw new TerminalMailProcessingException("NO_API_CLIENT",
                    "nessun client BERLink nel contesto: chiamata non eseguibile", null);
        }
        return ctx.api();
    }

    // ------------------------------------------------------------------ helper sul contenuto

    /**
     * Il testo dell'unico allegato che matcha. Assente o ambiguo sono entrambi errori terminali con
     * il nome dei file effettivamente presenti nel messaggio: e' la prima cosa che serve sapere.
     */
    protected String requireAttachmentText(MailContext ctx, String fileNameRegex) {
        List<MailAttachment> found = ctx.mail().attachments(fileNameRegex);
        if (found.isEmpty()) {
            throw terminal("MISSING_ATTACHMENT", "nessun allegato corrisponde a \"" + fileNameRegex
                    + "\"; presenti: " + ctx.mail().attachmentNames());
        }
        if (found.size() > 1) {
            throw terminal("AMBIGUOUS_ATTACHMENT", "piu' allegati corrispondono a \"" + fileNameRegex
                    + "\": " + found.stream().map(MailAttachment::fileName).toList());
        }
        MailAttachment attachment = found.get(0);
        if (attachment.truncated()) {
            throw terminal("ATTACHMENT_TRUNCATED", "allegato " + attachment.fileName()
                    + " troncato al limite configurato: il parsing lavorerebbe su dati incompleti");
        }
        String text = attachment.asText();
        if (text.isBlank()) {
            throw terminal("EMPTY_ATTACHMENT", "allegato " + attachment.fileName() + " vuoto");
        }
        return text;
    }

    protected String requireBodyText(MailContext ctx) {
        String body = ctx.mail().bodyText();
        if (body == null || body.isBlank()) {
            throw terminal("EMPTY_BODY", "il body della mail e' vuoto"
                    + (ctx.mail().bodyHtml() != null ? " (c'e' solo HTML non riducibile a testo)" : ""));
        }
        return body;
    }

    protected List<String> lines(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split("\r?\n")).toList();
    }

    /** Il gruppo richiesto, oppure un errore terminale che dice cosa si cercava e in cosa. */
    protected String requireGroup(String pattern, String text, int group, String what) {
        String value = RegexUtil.firstGroup(pattern, text, group, false, RegexUtil.DEFAULT_MAX_STEPS);
        if (value == null) {
            throw terminal("BAD_FORMAT", what + " non trovato: il pattern " + pattern
                    + " non matcha (" + preview(text) + ")");
        }
        return value;
    }

    protected LocalDate parseDate(String value, String pattern, String what) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
        } catch (DateTimeParseException e) {
            throw terminal("BAD_FORMAT", what + ": \"" + value + "\" non e' una data nel formato " + pattern);
        }
    }

    private String preview(String text) {
        if (text == null) {
            return "testo assente";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return "testo: \"" + (flat.length() > 120 ? flat.substring(0, 120) + "..." : flat) + "\"";
    }

    // ------------------------------------------------------------------ helper sugli errori

    protected TerminalMailProcessingException terminal(String errorType, String message) {
        return new TerminalMailProcessingException(errorType, message, null);
    }

    protected RetryableMailProcessingException retry(String errorType, String message, Throwable cause) {
        return new RetryableMailProcessingException(errorType, message, cause);
    }

    protected RetryableMailProcessingException retry(String errorType, String message) {
        return new RetryableMailProcessingException(errorType, message, null);
    }
}
