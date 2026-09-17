package it.gruppobernardini.switchmail.support;

import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.MailAttachment;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Costruttori di comodo per i test: ParsedMail e RuleConfig senza Spring, senza IMAP, senza DB. */
public final class MailFixtures {

    public static final Instant T0 = Instant.parse("2026-09-17T08:00:00Z");

    private MailFixtures() {
    }

    public static ParsedMail mail(String from, String subject, String body, MailAttachment... attachments) {
        return new ParsedMail(1L, "casella-test", "INBOX", 42L, 7L, "<msg-1@ferrovie.it>",
                from, null, List.of("ufficio@gruppobernardini.it"), subject,
                T0, T0, body, null, List.of(attachments), body == null ? 0 : body.length(), List.of());
    }

    public static ParsedMail mail(String from, String displayName, String subject, String body,
                                  long uid, List<MailAttachment> attachments, List<String> warnings) {
        return new ParsedMail(1L, "casella-test", "INBOX", 42L, uid, "<msg-" + uid + "@ferrovie.it>",
                from, displayName, List.of("ufficio@gruppobernardini.it"), subject,
                T0, T0, body, null, attachments, body == null ? 0 : body.length(), warnings);
    }

    public static MailAttachment txt(String fileName, String content) {
        return new MailAttachment(fileName, "text/plain", StandardCharsets.UTF_8,
                content.getBytes(StandardCharsets.UTF_8), false);
    }

    public static MailAttachment truncated(String fileName, String content) {
        return new MailAttachment(fileName, "text/plain", StandardCharsets.UTF_8,
                content.getBytes(StandardCharsets.UTF_8), true);
    }

    public static RuleConfig rule(long id, String name, int priority, String processorId,
                                  FieldCheck sender, FieldCheck subject, FieldCheck attachment) {
        return new RuleConfig(id, name, null, null, true, priority, true,
                sender, subject, attachment, false, processorId, "{}", 3, T0, T0);
    }

    public static RuleConfig simpleRule(String processorId, String paramsJson, boolean requireAttachment) {
        return new RuleConfig(1L, "regola-test", null, null, true, 100, true,
                FieldCheck.wildcard(), new FieldCheck("TEST", FieldCheck.MatchMode.CONTAINS, false),
                FieldCheck.wildcard(), requireAttachment, processorId, paramsJson, 3, T0, T0);
    }
}
