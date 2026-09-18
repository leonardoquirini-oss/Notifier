package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.MailAttachment;
import it.gruppobernardini.switchmail.model.ParsedMail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lettura dei Map in ingresso e costruzione delle viste in uscita.
 *
 * <p>I controller della piattaforma parlano JSON come {@code Map<String,Object>} (niente @Valid,
 * niente DTO di richiesta): questi helper stanno in un posto solo invece di essere ricopiati in ogni
 * controller.
 */
final class Payloads {

    private Payloads() {
    }

    static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String require(Object o, String field) {
        String s = str(o);
        if (s == null) {
            throw new IllegalArgumentException("campo obbligatorio: " + field);
        }
        return s;
    }

    static Integer intg(Object o) {
        if (o == null || (o instanceof String s && s.isBlank())) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("valore numerico non valido: " + o);
        }
    }

    static int intg(Object o, int def) {
        Integer v = intg(o);
        return v == null ? def : v;
    }

    static Long lng(Object o) {
        if (o == null || (o instanceof String s && s.isBlank())) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("identificativo non valido: " + o);
        }
    }

    static boolean bool(Object o, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return def;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s);
    }

    static FieldCheck fieldCheck(Map<String, Object> body, String prefix) {
        String pattern = str(body.get(prefix + "Pattern"));
        String mode = str(body.get(prefix + "Match"));
        boolean caseSensitive = bool(body.get(prefix + "CaseSensitive"), false);
        FieldCheck.MatchMode matchMode;
        try {
            matchMode = mode == null ? FieldCheck.MatchMode.CONTAINS
                    : FieldCheck.MatchMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("modalita' di confronto non valida per " + prefix + ": " + mode);
        }
        return new FieldCheck(pattern, matchMode, caseSensitive);
    }

    /**
     * Vista compatta di una mail: gli allegati come metadati, mai il contenuto. Serve a "vedi parsed"
     * e a /ruletest, dove il punto e' capire cosa ha visto il parser - non scaricare i byte.
     */
    static Map<String, Object> mailView(ParsedMail mail) {
        if (mail == null) {
            return Map.of();
        }
        List<Map<String, Object>> attachments = mail.attachments().stream()
                .map(Payloads::attachmentView)
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("accountId", mail.accountId());
        view.put("accountName", mail.accountName());
        view.put("folder", mail.folder());
        view.put("uidValidity", mail.uidValidity());
        view.put("uid", mail.uid());
        view.put("internetMessageId", mail.internetMessageId());
        view.put("from", mail.fromAddress());
        view.put("fromName", mail.fromDisplayName());
        view.put("fromFull", mail.fromFull());
        view.put("to", mail.toAddresses());
        view.put("subject", mail.subject());
        view.put("sentAt", mail.sentAt());
        view.put("receivedAt", mail.receivedAt());
        view.put("bodyText", mail.bodyText());
        view.put("hasHtml", mail.bodyHtml() != null);
        view.put("attachments", attachments);
        view.put("rawSizeBytes", mail.rawSizeBytes());
        view.put("warnings", mail.extractionWarnings());
        return view;
    }

    private static Map<String, Object> attachmentView(MailAttachment attachment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fileName", attachment.fileName());
        view.put("contentType", attachment.contentType());
        view.put("charset", attachment.charset() == null ? null : attachment.charset().name());
        view.put("sizeBytes", attachment.sizeBytes());
        view.put("truncated", attachment.truncated());
        String text = attachment.asText();
        view.put("preview", text.length() <= 2000 ? text : text.substring(0, 2000) + "\n...");
        return view;
    }
}
