package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.model.MailAttachment;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.service.MailContentExtractor;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixture .eml reali, nessun server IMAP, nessuno Spring context. */
class MailContentExtractorTest {

    private final SwitchMailProperties props = new SwitchMailProperties();
    private final MailContentExtractor extractor = new MailContentExtractor(props);

    private ParsedMail parse(String fixture) {
        try (InputStream in = getClass().getResourceAsStream("/mail/" + fixture)) {
            assertThat(in).as("fixture /mail/%s", fixture).isNotNull();
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), in);
            return extractor.extract(message, 1L, "casella-test", "INBOX", 42L, 7L, 1000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mailSemplice() {
        ParsedMail mail = parse("simple.eml");

        assertThat(mail.fromAddress()).isEqualTo("mario.rossi@ferrovie.it");
        assertThat(mail.fromDisplayName()).isEqualTo("Mario Rossi");
        assertThat(mail.fromFull()).isEqualTo("\"Mario Rossi\" <mario.rossi@ferrovie.it>");
        assertThat(mail.subject()).isEqualTo("AVVISI PARTENZA TRENO 4521 17/09/2026");
        assertThat(mail.bodyText()).contains("Binario 7");
        assertThat(mail.attachments()).isEmpty();
        assertThat(mail.extractionWarnings()).isEmpty();
        assertThat(mail.dedupKey()).isEqualTo("1:INBOX:42:7");
    }

    @Test
    @DisplayName("multipart/alternative: vince text/plain, l'HTML resta disponibile a parte")
    void alternative() {
        ParsedMail mail = parse("alternative.eml");

        assertThat(mail.bodyText()).contains("TERMINAL: GE88").doesNotContain("<p>");
        assertThat(mail.bodyHtml()).contains("<p>TERMINAL: GE88</p>");
        assertThat(mail.extractionWarnings()).isEmpty();
    }

    @Test
    void multipartMixedConAllegatoTxt() {
        ParsedMail mail = parse("mixed-txt.eml");

        assertThat(mail.bodyText()).contains("In allegato il dettaglio");
        assertThat(mail.attachmentNames()).containsExactly("treno.txt");
        MailAttachment attachment = mail.attachment("(?i).*\\.txt$").orElseThrow();
        assertThat(attachment.asText()).contains("TRENO;4521").contains("DATA;17/09/2026");
        assertThat(attachment.truncated()).isFalse();
    }

    @Test
    @DisplayName("charset dichiarato utf-8 ma byte CP1252: si rilegge e si dichiara nel warning")
    void charsetDichiaratoSbagliato() {
        ParsedMail mail = parse("cp1252-undeclared.eml");

        assertThat(mail.bodyText()).contains("è già partito").contains("perché").contains("€");
        assertThat(mail.extractionWarnings())
                .anySatisfy(w -> assertThat(w).contains("windows-1252"));
    }

    @Test
    @DisplayName("nome allegato RFC 2047 decodificato")
    void filenameCodificato() {
        ParsedMail mail = parse("rfc2047-filename.eml");
        assertThat(mail.attachmentNames()).containsExactly("relazione perché sì.txt");
    }

    @Test
    void multipartAnnidato() {
        ParsedMail mail = parse("nested.eml");

        assertThat(mail.bodyText()).contains("TESTO ANNIDATO");
        assertThat(mail.attachmentNames()).containsExactly("listato.pdf");
    }

    @Test
    @DisplayName("solo HTML: il body viene ricavato dall'HTML e lo dice")
    void soloHtml() {
        ParsedMail mail = parse("html-only.eml");

        assertThat(mail.bodyText()).contains("Prima riga").contains("Seconda & ultima");
        assertThat(mail.bodyText()).doesNotContain("var x").doesNotContain("color:red");
        assertThat(mail.extractionWarnings()).anySatisfy(w -> assertThat(w).contains("HTML"));
    }

    @Test
    @DisplayName("allegato oltre il cap: troncato con warning, non OutOfMemory")
    void allegatoTroncato() {
        props.getMail().setMaxAttachmentBytes(1024);

        ParsedMail mail = parse("big-attachment.eml");

        MailAttachment attachment = mail.attachment("grande").orElseThrow();
        assertThat(attachment.truncated()).isTrue();
        assertThat(attachment.sizeBytes()).isEqualTo(1024);
        assertThat(mail.extractionWarnings()).anySatisfy(w -> assertThat(w).contains("troncato"));
    }

    @Test
    void attachmentsRestituisceTuttiIMatch() {
        ParsedMail mail = parse("mixed-txt.eml");
        assertThat(mail.attachments("(?i).*\\.txt$")).hasSize(1);
        assertThat(mail.attachments("(?i).*\\.pdf$")).isEmpty();
        assertThat(mail.hasAttachments()).isTrue();
    }
}
