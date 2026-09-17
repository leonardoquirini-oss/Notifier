package it.gruppobernardini.switchmail.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Un allegato gia' materializzato in memoria: il contenuto viene letto mentre la folder IMAP e'
 * ancora aperta, cosi' i processori non toccano mai jakarta.mail.
 *
 * @param truncated true se il contenuto e' stato tagliato a switchmail.mail.max-attachment-bytes
 */
public record MailAttachment(
        String fileName,
        String contentType,
        Charset charset,
        byte[] content,
        boolean truncated) {

    public MailAttachment {
        if (content == null) {
            content = new byte[0];
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
    }

    public String asText() {
        return new String(content, charset);
    }

    public long sizeBytes() {
        return content.length;
    }
}
