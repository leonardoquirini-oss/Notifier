package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.PostAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le property della sessione IMAP sono la parte del sistema che tiene la promessa di non
 * distruttivita', e sono anche quelle che qualcuno potrebbe "ripulire" in un refactor. Qui sono
 * inchiodate una per una, con il motivo scritto accanto.
 */
class ImapSessionPropertiesTest {

    private final SwitchMailProperties props = new SwitchMailProperties();
    private final ImapMailReader reader = new ImapMailReader(
            new MailContentExtractor(props), props, Clock.systemUTC());

    private MailAccount account(boolean ssl, boolean trustAll, AccessMode mode) {
        return new MailAccount(1L, "casella", "imap.example.it", ssl ? 993 : 143, ssl, false, trustAll,
                "service", null, "INBOX", mode, PostAction.NONE, null, "0 */2 * * * *", 50, 1,
                7000, 21000, true, null, null, null, null, 0, null, null);
    }

    @Test
    @DisplayName("peek = true SEMPRE: senza, getContent() setta \\Seen anche su folder read-only")
    void peekSempreAttivo() {
        assertThat(reader.sessionProperties(account(true, false, AccessMode.READ_ONLY)))
                .containsEntry("mail.imaps.peek", "true");
        assertThat(reader.sessionProperties(account(false, false, AccessMode.READ_ONLY)))
                .containsEntry("mail.imap.peek", "true");
        // vale anche sulla casella posseduta: li' i flag li cambia la post-action, non il fetch
        assertThat(reader.sessionProperties(account(true, false, AccessMode.OWNED)))
                .containsEntry("mail.imaps.peek", "true");
    }

    @Test
    void timeoutDallaRigaAccount() {
        Properties p = reader.sessionProperties(account(true, false, AccessMode.READ_ONLY));
        assertThat(p).containsEntry("mail.imaps.connectiontimeout", "7000")
                .containsEntry("mail.imaps.timeout", "21000")
                .containsEntry("mail.imaps.writetimeout", "21000")
                .containsEntry("mail.store.protocol", "imaps");
    }

    @Test
    @DisplayName("la verifica del certificato si rilassa solo se richiesto esplicitamente")
    void verificaCertificato() {
        assertThat(reader.sessionProperties(account(true, false, AccessMode.READ_ONLY)))
                .containsEntry("mail.imaps.ssl.checkserveridentity", "true")
                .doesNotContainKey("mail.imaps.ssl.trust");

        assertThat(reader.sessionProperties(account(true, true, AccessMode.READ_ONLY)))
                .containsEntry("mail.imaps.ssl.checkserveridentity", "false")
                .containsEntry("mail.imaps.ssl.trust", "*");
    }

    @Test
    @DisplayName("mail.debug spegne di default: con true jakarta.mail stampa il LOGIN, password inclusa")
    void debugSpentoDiDefault() {
        assertThat(reader.sessionProperties(account(true, false, AccessMode.READ_ONLY)))
                .containsEntry("mail.debug", "false");

        props.getImap().setDebug(true);
        assertThat(reader.sessionProperties(account(true, false, AccessMode.READ_ONLY)))
                .containsEntry("mail.debug", "true");
    }
}
