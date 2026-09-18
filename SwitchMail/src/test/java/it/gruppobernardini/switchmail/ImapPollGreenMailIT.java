package it.gruppobernardini.switchmail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import it.gruppobernardini.switchmail.dao.ProcessingAttemptDao;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.PostAction;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import it.gruppobernardini.switchmail.processor.RetryableMailProcessingException;
import it.gruppobernardini.switchmail.service.AccountService;
import it.gruppobernardini.switchmail.service.MailPollService;
import it.gruppobernardini.switchmail.service.RetryScheduler;
import it.gruppobernardini.switchmail.support.MutableClock;
import it.gruppobernardini.switchmail.support.RecordingProcessor;
import it.gruppobernardini.switchmail.support.TestClockConfig;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il poll completo contro un server IMAP vero (in-process).
 *
 * <p>Toglie l'integration test dal percorso critico della "fase 2" del design doc - non serve
 * aspettare le credenziali dell'IT per sapere se la pipeline funziona - e soprattutto trasforma la
 * garanzia di non distruttivita' da promessa architetturale ad assert in CI.
 */
@SpringBootTest(classes = {SwitchMailApplication.class, TestClockConfig.class}, properties = {
        "switchmail.poll.enabled=false",
        "switchmail.security.creds-key=c3dpdGNobWFpbC10ZXN0LWtleS0zMi1ieXRlcyEhISE=",
        "berlink.api.base-url=",
        "berlink.api.api-key=test",
        "health.api-key=test"
})
class ImapPollGreenMailIT {

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    private static Path dbFile;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("switchmail.db.path", () -> {
            if (dbFile == null) {
                try {
                    dbFile = Files.createTempFile("switchmail-it-", ".db");
                    Files.deleteIfExists(dbFile);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return dbFile.toAbsolutePath().toString();
        });
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accountService;
    @Autowired private MailPollService pollService;
    @Autowired private RetryScheduler retryScheduler;
    @Autowired private ProcessingLogDao logDao;
    @Autowired private ProcessingAttemptDao attemptDao;
    @Autowired private RecordingProcessor processor;
    @Autowired private Clock clock;

    private long accountId;

    private static final String USER = "service@test.local";
    private static final String PASSWORD = "segreta";

    @BeforeEach
    void setUp() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        jdbc.update("DELETE FROM mail_processing_attempt");
        jdbc.update("DELETE FROM mail_raw");
        jdbc.update("DELETE FROM mail_processing_log");
        jdbc.update("DELETE FROM mail_rule");
        jdbc.update("DELETE FROM mail_folder_state");
        jdbc.update("DELETE FROM mail_account");
        processor.invocations().clear();
        processor.behaviour(ctx -> ProcessingOutcome.success("elaborata",
                java.util.Map.of("uid", ctx.mail().uid(), "subject", String.valueOf(ctx.mail().subject())), null));
        ((MutableClock) clock).set(java.time.Instant.parse("2026-09-17T08:00:00Z"));

        greenMail.setUser(USER, USER, PASSWORD);
        accountId = createAccount();
    }

    // ---------------------------------------------------------------- 1. seed

    private void seedTrainMail(String subject) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("mario.rossi@ferrovie.it", "Mario Rossi"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, USER);
        message.setSubject(subject);

        MimeBodyPart body = new MimeBodyPart();
        body.setText("In allegato il dettaglio.", "UTF-8");
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText("TRENO;4521\nDATA;17/09/2026\n", "UTF-8");
        attachment.setFileName("treno.txt");
        attachment.setDisposition(jakarta.mail.Part.ATTACHMENT);

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(body);
        multipart.addBodyPart(attachment);
        message.setContent(multipart);

        greenMail.getManagers().getImapHostManager()
                .getInbox(greenMail.getManagers().getUserManager().getUser(USER))
                .store(message);
    }

    // ---------------------------------------------------------------- 2. config

    private long createAccount() {
        MailAccount draft = new MailAccount(null, "casella-di-prova", "127.0.0.1",
                greenMail.getImap().getPort(), false, false, false, USER, null, "INBOX",
                AccessMode.READ_ONLY, PostAction.NONE, null, "0 */2 * * * *", 50, 1, 5000, 5000, true,
                null, null, null, null, 0, null, null);
        return accountService.create(draft, PASSWORD);
    }

    private long createRule(String subjectPattern) {
        return jdbc.queryForObject("""
                INSERT INTO mail_rule (name, account_id, enabled, priority, stop_on_match,
                    subject_pattern, subject_match, subject_case_sensitive, sender_match, sender_case_sensitive,
                    attachment_match, attachment_case_sensitive, require_attachment, processor_id, params_json,
                    max_attempts, created_at, updated_at)
                VALUES (?, ?, 1, 100, 1, ?, 'CONTAINS', 0, 'CONTAINS', 0, 'CONTAINS', 0, 1, 'it-proc', '{}', 2,
                    '2026-09-17T08:00:00.000Z', '2026-09-17T08:00:00.000Z')
                RETURNING id
                """, Long.class, "regola-" + subjectPattern, accountId, subjectPattern);
    }

    // ---------------------------------------------------------------- 3-4. poll ed esito

    @Test
    @Order(1)
    @DisplayName("poll: la mail viene elaborata una volta, con allegato e parametri visti dal processore")
    void pollElabora() throws Exception {
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        createRule("AVVISI PARTENZA TRENO");

        MailPollService.PollReport report = pollService.pollAccount(accountId);

        assertThat(report.ok()).isTrue();
        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.ingested()).isEqualTo(1);

        List<ProcessingLogEntry> rows = allRows();
        assertThat(rows).hasSize(1);
        ProcessingLogEntry entry = rows.get(0);
        assertThat(entry.status().name()).isEqualTo("SUCCESS");
        assertThat(entry.uidValidity()).isPositive();
        assertThat(entry.uid()).isPositive();
        assertThat(entry.extractedJson()).contains("AVVISI PARTENZA TRENO 4521");
        assertThat(entry.attachmentNames()).containsExactly("treno.txt");

        assertThat(processor.count()).isEqualTo(1);
        var seen = processor.invocations().get(0);
        assertThat(seen.mail().attachment("treno").orElseThrow().asText()).contains("TRENO;4521");
        assertThat(seen.dryRun()).isFalse();
    }

    // ---------------------------------------------------------------- 5. non distruttivita'

    @Test
    @DisplayName("dopo un poll completo la mail NON risulta letta ne' cancellata")
    void pollNonTocca() throws Exception {
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        createRule("AVVISI PARTENZA TRENO");

        pollService.pollAccount(accountId);

        // Connessione separata, aperta in scrittura: se il poll avesse toccato i flag, si vedrebbe qui.
        Store store = imapStore();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        try {
            assertThat(inbox.getMessageCount()).isEqualTo(1);
            jakarta.mail.Message message = inbox.getMessage(1);
            assertThat(message.isSet(Flags.Flag.SEEN)).as("flag \\Seen").isFalse();
            assertThat(message.isSet(Flags.Flag.DELETED)).as("flag \\Deleted").isFalse();
        } finally {
            inbox.close(false);
            store.close();
        }
    }

    // ---------------------------------------------------------------- 6-7. idempotenza e high-water

    @Test
    @DisplayName("ripollare non rielabora: il claim regge tra un poll e l'altro")
    void idempotenza() throws Exception {
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        createRule("AVVISI PARTENZA TRENO");

        pollService.pollAccount(accountId);
        pollService.pollAccount(accountId);

        assertThat(allRows()).hasSize(1);
        assertThat(processor.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("high-water mark: il secondo poll fetcha solo la mail nuova (trappola LASTUID)")
    void highWaterMark() throws Exception {
        createRule("AVVISI PARTENZA TRENO");
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        pollService.pollAccount(accountId);

        seedTrainMail("AVVISI PARTENZA TRENO 4522 18/09/2026");
        MailPollService.PollReport secondo = pollService.pollAccount(accountId);

        assertThat(secondo.fetched()).as("solo la mail nuova viene scaricata").isEqualTo(1);
        assertThat(allRows()).hasSize(2);
        assertThat(processor.count()).isEqualTo(2);

        // e un terzo poll a vuoto non deve riscaricare l'ultima mail
        assertThat(pollService.pollAccount(accountId).fetched()).isZero();
    }

    // ---------------------------------------------------------------- 8. crash-safety

    @Test
    @DisplayName("claim orfano: recuperato come INTERRUPTED e messo in dead-letter per decisione umana")
    void recuperoClaimOrfano() throws Exception {
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        createRule("AVVISI PARTENZA TRENO");
        pollService.pollAccount(accountId);
        long logId = allRows().get(0).id();

        // simula un processo ucciso a meta': riga IN_PROGRESS con claim vecchio
        jdbc.update("UPDATE mail_processing_log SET status = 'IN_PROGRESS', claimed_at = ? WHERE id = ?",
                "2026-09-17T07:40:00.000Z", logId);

        int recovered = retryScheduler.recoverStaleClaims();

        assertThat(recovered).isEqualTo(1);
        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("DEAD_LETTER");
        assertThat(entry.errorType()).isEqualTo("INTERRUPTED");
        assertThat(entry.message()).contains("potrebbe essere gia'");
        assertThat(attemptDao.findByLog(logId))
                .anySatisfy(a -> assertThat(a.get("errorType")).isEqualTo("INTERRUPTED"));
    }

    // ---------------------------------------------------------------- 9. retry

    @Test
    @DisplayName("errore ritentabile: retry programmato, poi riuscito allo scadere del backoff")
    void retryAutomatico() throws Exception {
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        createRule("AVVISI PARTENZA TRENO");
        AtomicInteger tentativi = new AtomicInteger();
        processor.behaviour(ctx -> {
            if (tentativi.incrementAndGet() == 1) {
                throw new RetryableMailProcessingException("BERLINK_503", "BERLink non disponibile", null);
            }
            return ProcessingOutcome.success("riuscita al secondo tentativo",
                    java.util.Map.of("uid", ctx.mail().uid()), null);
        });

        pollService.pollAccount(accountId);
        long logId = allRows().get(0).id();
        assertThat(logDao.find(logId).orElseThrow().status().name()).isEqualTo("RETRY_SCHEDULED");

        ((MutableClock) clock).advance(Duration.ofMinutes(5));
        int eseguiti = retryScheduler.runDueRetries();

        assertThat(eseguiti).isEqualTo(1);
        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("SUCCESS");
        assertThat(entry.attempt()).isEqualTo(2);
        assertThat(attemptDao.findByLog(logId)).hasSize(2);
    }

    // ---------------------------------------------------------------- 10. NO_RULE

    @Test
    @DisplayName("nessuna regola: riga NO_RULE con il MIME archiviato, pronta per scriverne una")
    void nessunaRegola() throws Exception {
        seedTrainMail("Comunicazione che non interessa");

        pollService.pollAccount(accountId);

        ProcessingLogEntry entry = allRows().get(0);
        assertThat(entry.status().name()).isEqualTo("NO_RULE");
        assertThat(entry.rawAvailable()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mail_raw", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 11. reset UIDVALIDITY

    @Test
    @DisplayName("reset di UIDVALIDITY: rilevato, stato azzerato, e nessuna doppia elaborazione")
    void resetUidValidity() throws Exception {
        seedTrainMail("AVVISI PARTENZA TRENO 4521 17/09/2026");
        createRule("AVVISI PARTENZA TRENO");
        pollService.pollAccount(accountId);
        assertThat(processor.count()).isEqualTo(1);

        // GreenMail non cambia UIDVALIDITY a comando: si simula lo stato che il servizio vedrebbe.
        jdbc.update("UPDATE mail_folder_state SET uid_validity = uid_validity + 1");

        MailPollService.PollReport reset = pollService.pollAccount(accountId);
        assertThat(reset.uidValidityReset()).isTrue();
        assertThat(pollService.isResetPending(accountId)).isTrue();

        MailPollService.PollReport dopo = pollService.pollAccount(accountId);

        assertThat(dopo.ok()).isTrue();
        // Ripartendo da last_uid = 0 la mail viene riscaricata, ma non rielaborata: qui la riconosce
        // il claim (la UIDVALIDITY reale non e' cambiata). Quando cambia davvero, la stessa difesa la
        // fa il Message-ID - percorso coperto da MailIngestServiceTest.skipDopoResetUidValidity.
        assertThat(processor.count()).as("la mail non viene rielaborata").isEqualTo(1);
        assertThat(allRows()).hasSize(1);
        assertThat(allRows().get(0).status().name()).isEqualTo("SUCCESS");
    }

    // ---------------------------------------------------------------- helper

    private List<ProcessingLogEntry> allRows() {
        return logDao.page(new it.gruppobernardini.switchmail.dto.LogFilter(
                null, List.of(), null, null, null, null, null, null, 100));
    }

    private Store imapStore() throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.peek", "true");
        Store store = Session.getInstance(props).getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), USER, PASSWORD);
        return store;
    }
}
