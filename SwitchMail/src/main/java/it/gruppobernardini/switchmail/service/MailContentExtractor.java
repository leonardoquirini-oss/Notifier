package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.model.MailAttachment;
import it.gruppobernardini.switchmail.model.ParsedMail;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Da MimeMessage a {@link ParsedMail}.
 *
 * <p>Sta in un componente a se' e non tra gli helper della base astratta per tre motivi: gira una
 * volta per mail <i>prima</i> di qualsiasi processore (mentre la folder e' ancora aperta), deve
 * essere testabile da fixture .eml senza processore e senza Spring, e serve anche a /ruletest
 * (upload di un .eml) e a /logs ("vedi parsed").
 */
@Component
@Slf4j
public class MailContentExtractor {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final SwitchMailProperties props;

    public MailContentExtractor(SwitchMailProperties props) {
        this.props = props;
    }

    /**
     * Ricostruisce una mail dal MIME grezzo archiviato.
     *
     * <p>Sta qui e non nei service perche' jakarta.mail non deve uscire da questo file e dal reader:
     * chi ricostruisce una mail per un retry o per /ruletest non deve sapere cosa sia una
     * MimeMessage.
     */
    public ParsedMail extract(byte[] raw, long accountId, String accountName, String folder,
                              long uidValidity, long uid) {
        try {
            MimeMessage message = new MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
                    new java.io.ByteArrayInputStream(raw));
            return extract(message, accountId, accountName, folder, uidValidity, uid, raw.length);
        } catch (Exception e) {
            throw new IllegalArgumentException("MIME non leggibile: " + e.getMessage(), e);
        }
    }

    public ParsedMail extract(MimeMessage message, long accountId, String accountName, String folder,
                              long uidValidity, long uid, int rawSizeBytes) {
        List<String> warnings = new ArrayList<>();
        Parts parts = new Parts();
        try {
            walk(message, parts, warnings, false);
        } catch (Exception e) {
            // Una parte illeggibile non annulla la mail: il resto si elabora, e il warning e' visibile.
            warnings.add("walk MIME interrotto: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            log.warn("Walk MIME interrotto su uid={}", uid, e);
        }

        String from = null;
        String fromName = null;
        List<String> to = new ArrayList<>();
        String subject = null;
        Instant sentAt = null;
        Instant receivedAt = null;
        String messageId = null;
        try {
            Address[] fromAddresses = message.getFrom();
            if (fromAddresses != null && fromAddresses.length > 0 && fromAddresses[0] instanceof InternetAddress ia) {
                from = ia.getAddress();
                fromName = ia.getPersonal();
            } else if (fromAddresses != null && fromAddresses.length > 0) {
                from = fromAddresses[0].toString();
            }
            Address[] recipients = message.getRecipients(Message.RecipientType.TO);
            if (recipients != null) {
                for (Address a : recipients) {
                    to.add(a instanceof InternetAddress ia ? ia.getAddress() : a.toString());
                }
            }
            subject = message.getSubject();
            sentAt = message.getSentDate() == null ? null : message.getSentDate().toInstant();
            receivedAt = message.getReceivedDate() == null ? null : message.getReceivedDate().toInstant();
            messageId = message.getMessageID();
        } catch (MessagingException e) {
            warnings.add("header non leggibili: " + e.getMessage());
        }

        if (parts.text == null && parts.html != null) {
            parts.text = htmlToText(parts.html);
            warnings.add("nessuna parte text/plain: il body e' stato ricavato dall'HTML");
        }

        return new ParsedMail(accountId, accountName, folder, uidValidity, uid, messageId,
                from, fromName, to, subject, sentAt, receivedAt,
                parts.text == null ? "" : parts.text, parts.html,
                parts.attachments, rawSizeBytes, warnings);
    }

    private static final class Parts {
        String text;
        String html;
        final List<MailAttachment> attachments = new ArrayList<>();
    }

    private void walk(Part part, Parts out, List<String> warnings, boolean insideAlternative)
            throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            jakarta.mail.Multipart mp = (jakarta.mail.Multipart) part.getContent();
            boolean alternative = part.isMimeType("multipart/alternative");
            for (int i = 0; i < mp.getCount(); i++) {
                walk(mp.getBodyPart(i), out, warnings, insideAlternative || alternative);
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            Object nested = part.getContent();
            if (nested instanceof Part p) {
                walk(p, out, warnings, insideAlternative);
            }
            return;
        }

        if (isAttachment(part)) {
            out.attachments.add(readAttachment(part, warnings));
            return;
        }

        if (part.isMimeType("text/plain")) {
            String text = readText(part, warnings);
            out.text = out.text == null ? text : out.text + "\n" + text;
        } else if (part.isMimeType("text/html")) {
            String html = readText(part, warnings);
            out.html = out.html == null ? html : out.html + "\n" + html;
        } else {
            // Parte non testuale senza filename: la si registra come allegato senza nome, cosi' non
            // sparisce in silenzio dalla vista di chi indaga su un formato.
            out.attachments.add(readAttachment(part, warnings));
        }
    }

    /** Allegato = Content-Disposition attachment <b>oppure</b> un filename presente (inline inclusi). */
    private boolean isAttachment(Part part) throws MessagingException {
        String disposition = part.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            return true;
        }
        String fileName = part.getFileName();
        return fileName != null && !fileName.isBlank();
    }

    private MailAttachment readAttachment(Part part, List<String> warnings)
            throws MessagingException, IOException {
        String fileName = decodeFileName(part.getFileName(), warnings);
        String contentType = safeContentType(part);
        long max = props.getMail().getMaxAttachmentBytes();

        Limited limited = readLimited(part.getInputStream(), max);
        if (limited.truncated) {
            warnings.add("allegato " + fileName + " troncato a " + max + " byte: il contenuto e' incompleto");
        }
        Charset charset = charsetOf(contentType);
        return new MailAttachment(fileName, contentType, charset == null ? StandardCharsets.UTF_8 : charset,
                limited.bytes, limited.truncated);
    }

    /**
     * Decodifica il testo con la catena dichiarato -&gt; windows-1252 -&gt; UTF-8 con REPLACE.
     *
     * <p>I mittenti Exchange italiani emettono CP1252 molto piu' spesso di quanto lo dichiarino: una
     * mail "UTF-8" con un singolo 0xE0 al posto di "a accentata" fallisce la decodifica stretta.
     * Ogni passo della catena lascia un warning, perche' il charset indovinato di nascosto e'
     * l'archetipo del silent failure.
     */
    private String readText(Part part, List<String> warnings) throws MessagingException, IOException {
        Limited limited = readLimited(part.getInputStream(), props.getMail().getMaxAttachmentBytes());
        if (limited.truncated) {
            warnings.add("parte di testo troncata a " + props.getMail().getMaxAttachmentBytes() + " byte");
        }
        Charset declared = charsetOf(safeContentType(part));

        if (declared != null) {
            String decoded = decodeStrict(limited.bytes, declared);
            if (decoded != null) {
                return decoded;
            }
            warnings.add("charset dichiarato " + declared.name() + " non valido per il contenuto: "
                    + "riletto come windows-1252");
        }
        String cp1252 = decodeStrict(limited.bytes, CP1252);
        if (cp1252 != null) {
            if (declared == null) {
                warnings.add("nessun charset dichiarato: contenuto letto come windows-1252");
            }
            return cp1252;
        }
        warnings.add("contenuto non decodificabile: letto come UTF-8 con caratteri sostituiti");
        return new String(limited.bytes, StandardCharsets.UTF_8);
    }

    private String decodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String decodeFileName(String fileName, List<String> warnings) {
        if (fileName == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(fileName);
        } catch (Exception e) {
            warnings.add("nome allegato non decodificabile (RFC 2047): " + fileName);
            return fileName;
        }
    }

    private String safeContentType(Part part) {
        try {
            return part.getContentType();
        } catch (MessagingException e) {
            return null;
        }
    }

    private Charset charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        try {
            String name = new ContentType(contentType).getParameter("charset");
            if (name == null || name.isBlank()) {
                return null;
            }
            return Charset.forName(MimeUtility.javaCharset(name.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private record Limited(byte[] bytes, boolean truncated) {
    }

    /** Legge al massimo max byte: un allegato fuori misura viene troncato, non mandato in OOM. */
    private Limited readLimited(InputStream in, long max) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buf)) != -1) {
                if (total + read > max) {
                    out.write(buf, 0, (int) (max - total));
                    return new Limited(out.toByteArray(), true);
                }
                out.write(buf, 0, read);
                total += read;
            }
            return new Limited(out.toByteArray(), false);
        }
    }

    /** Riduzione HTML -&gt; testo, volutamente grezza: serve a leggere e a fare match, non a rendere. */
    static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = s.replace("&nbsp;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }
}
