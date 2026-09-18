package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.config.BerlinkApiConfig;
import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dao.ProcessingAttemptDao;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.dao.RawMailDao;
import it.gruppobernardini.switchmail.dao.RuleDao;
import it.gruppobernardini.switchmail.dto.FetchedMail;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import it.gruppobernardini.switchmail.processor.RetryableMailProcessingException;
import it.gruppobernardini.switchmail.processor.TerminalMailProcessingException;
import it.gruppobernardini.switchmail.service.AdminNotifier;
import it.gruppobernardini.switchmail.service.BerlinkApiClientFactory;
import it.gruppobernardini.switchmail.service.MailContentExtractor;
import it.gruppobernardini.switchmail.service.MailIngestService;
import it.gruppobernardini.switchmail.service.ProcessingRecorder;
import it.gruppobernardini.switchmail.service.RawMailStore;
import it.gruppobernardini.switchmail.service.RuleMatcher;
import it.gruppobernardini.switchmail.support.MailFixtures;
import it.gruppobernardini.switchmail.support.MutableClock;
import it.gruppobernardini.switchmail.support.RecordingProcessor;
import it.gruppobernardini.switchmail.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il comportamento su cui poggia tutto il resto: claim, dedup, esiti, retry, dead-letter.
 * Database SQLite vero (schema di produzione), nessun IMAP, nessuno Spring context.
 */
class MailIngestServiceTest {

    private TestDb db;
    private MutableClock clock;
    private SwitchMailProperties props;
    private ProcessingLogDao logDao;
    private ProcessingAttemptDao attemptDao;
    private RecordingProcessor processor;
    private RecordingProcessor secondo;
    private MailIngestService service;
    private long accountId;

    @BeforeEach
    void setUp() {
        db = new TestDb();
        clock = new MutableClock(Instant.parse("2026-09-17T08:00:00Z"));
        props = new SwitchMailProperties();

        logDao = new ProcessingLogDao(db.jdbc(), clock);
        attemptDao = new ProcessingAttemptDao(db.jdbc());
        RuleDao ruleDao = new RuleDao(db.jdbc(), clock);

        processor = new RecordingProcessor("test-proc");
        secondo = new RecordingProcessor("secondo-proc");
        MailSubProcessorRegistry registry = new MailSubProcessorRegistry(List.of(processor, secondo));
        registry.init();

        BerlinkApiConfig apiConfig = new BerlinkApiConfig();
        service = new MailIngestService(logDao, ruleDao, new RuleMatcher(props), registry,
                new RawMailStore(new RawMailDao(db.jdbc(), clock), props),
                new BerlinkApiClientFactory(new RestTemplate(), apiConfig),
                new ProcessingRecorder(logDao, attemptDao),
                new AdminNotifier(new RestTemplate(), apiConfig),
                new MailContentExtractor(props), props, clock);

        accountId = db.insertAccount("casella-test");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private FetchedMail mail(long uid, String subject) {
        ParsedMail parsed = new ParsedMail(accountId, "casella-test", "INBOX", 42L, uid,
                "<msg-" + uid + "@test>", "mario.rossi@ferrovie.it", "Mario Rossi", List.of(), subject,
                clock.instant(), clock.instant(), "corpo", null, List.of(), 100, List.of());
        return new FetchedMail(parsed, ("Subject: " + subject + "\r\n\r\ncorpo\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private long ruleFor(String processorId, String subjectPattern) {
        return db.insertRule("regola-" + processorId + "-" + subjectPattern, accountId, processorId,
                subjectPattern, 100, true, "{}");
    }

    @Test
    @DisplayName("il claim e' la dedup: la stessa mail non viene mai elaborata due volte")
    void dedup() {
        ruleFor("test-proc", "AVVISI");
        FetchedMail m = mail(7, "AVVISI PARTENZA TRENO 4521");

        Optional<Long> primo = service.ingestNew(m, false);
        Optional<Long> secondoTentativo = service.ingestNew(m, false);

        assertThat(primo).isPresent();
        assertThat(secondoTentativo).isEmpty();
        assertThat(db.count("mail_processing_log")).isEqualTo(1);
        assertThat(processor.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("nessuna regola: riga NO_RULE, MIME archiviato, niente scarto silenzioso")
    void nessunaRegola() {
        Long logId = service.ingestNew(mail(7, "Mail che non interessa a nessuno"), false).orElseThrow();

        assertThat(db.status(logId)).isEqualTo("NO_RULE");
        assertThat(db.count("mail_raw")).isEqualTo(1);
        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.message()).contains("Nessuna regola");
        assertThat(entry.rawAvailable()).isTrue();
    }

    @Test
    void successo() {
        long ruleId = ruleFor("test-proc", "AVVISI");

        Long logId = service.ingestNew(mail(7, "AVVISI PARTENZA TRENO 4521"), false).orElseThrow();

        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("SUCCESS");
        assertThat(entry.attempt()).isEqualTo(1);
        assertThat(entry.ruleId()).isEqualTo(ruleId);
        assertThat(entry.processorId()).isEqualTo("test-proc");
        assertThat(entry.matchedRuleIds()).containsExactly(ruleId);
        assertThat(entry.extractedJson()).contains("AVVISI PARTENZA TRENO 4521");
        assertThat(attemptDao.findByLog(logId)).singleElement()
                .satisfies(a -> assertThat(a.get("status")).isEqualTo("SUCCESS"));
    }

    @Test
    @DisplayName("SKIPPED resta consultabile: e' un esito, non un silenzio")
    void skipped() {
        ruleFor("test-proc", "AVVISI");
        processor.behaviour(ctx -> ProcessingOutcome.skipped("modalita' COLLECT: niente da fare"));

        Long logId = service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        assertThat(db.status(logId)).isEqualTo("SKIPPED");
        assertThat(logDao.find(logId).orElseThrow().message()).contains("COLLECT");
    }

    @Test
    @DisplayName("errore ritentabile: RETRY_SCHEDULED con next_retry_at nel futuro")
    void erroreRitentabile() {
        ruleFor("test-proc", "AVVISI");
        processor.behaviour(ctx -> {
            throw new RetryableMailProcessingException("BERLINK_503", "BERLink non disponibile", null);
        });

        Long logId = service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("RETRY_SCHEDULED");
        assertThat(entry.attempt()).isEqualTo(1);
        assertThat(entry.errorType()).isEqualTo("BERLINK_503");
        assertThat(entry.nextRetryAt()).isAfter(clock.instant());
        assertThat(attemptDao.findByLog(logId)).singleElement()
                .satisfies(a -> assertThat(a.get("status")).isEqualTo("FAILED_RETRYABLE"));
    }

    @Test
    @DisplayName("errore terminale: dead-letter al primo colpo, senza sprecare tentativi")
    void erroreTerminale() {
        ruleFor("test-proc", "AVVISI");
        processor.behaviour(ctx -> {
            throw new TerminalMailProcessingException("BAD_FORMAT", "allegato illeggibile", null);
        });

        Long logId = service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("DEAD_LETTER");
        assertThat(entry.errorType()).isEqualTo("BAD_FORMAT");
        assertThat(entry.errorStack()).contains("TerminalMailProcessingException");
    }

    @Test
    @DisplayName("una RuntimeException non classificata e' terminale: una NPE non guarisce al terzo tentativo")
    void erroreNonClassificato() {
        ruleFor("test-proc", "AVVISI");
        processor.behaviour(ctx -> {
            throw new IllegalStateException("boom");
        });

        Long logId = service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        assertThat(db.status(logId)).isEqualTo("DEAD_LETTER");
        assertThat(logDao.find(logId).orElseThrow().errorType()).isEqualTo("UNEXPECTED");
    }

    @Test
    @DisplayName("tentativi esauriti: dead-letter, e ogni tentativo resta nell'audit")
    void tentativiEsauriti() {
        ruleFor("test-proc", "AVVISI");
        processor.behaviour(ctx -> {
            throw new RetryableMailProcessingException("BERLINK_503", "giu'", null);
        });
        Long logId = service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        ParsedMail parsed = service.rebuildFromRaw(logDao.find(logId).orElseThrow()).orElseThrow();
        logDao.reclaim(logId);
        service.execute(logId, parsed, it.gruppobernardini.switchmail.model.TriggeredBy.RETRY_AUTO);
        logDao.reclaim(logId);
        service.execute(logId, parsed, it.gruppobernardini.switchmail.model.TriggeredBy.RETRY_AUTO);

        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("DEAD_LETTER");
        assertThat(entry.attempt()).isEqualTo(3);
        assertThat(attemptDao.findByLog(logId)).hasSize(3);
    }

    @Test
    @DisplayName("processore inesistente: dead-letter esplicita, mai uno skip silenzioso")
    void processoreSconosciuto() {
        db.insertRule("regola-rotta", accountId, "processore-che-non-esiste", "AVVISI", 100, true, "{}");

        Long logId = service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        ProcessingLogEntry entry = logDao.find(logId).orElseThrow();
        assertThat(entry.status().name()).isEqualTo("DEAD_LETTER");
        assertThat(entry.errorType()).isEqualTo("UNKNOWN_PROCESSOR");
    }

    @Test
    @DisplayName("stop_on_match = 0: due regole, due righe di audit, UNA riga di log")
    void stopOnMatchDisattivato() {
        db.insertRule("prima", accountId, "test-proc", "AVVISI", 10, false, "{}");
        db.insertRule("seconda", accountId, "secondo-proc", "TRENO", 20, true, "{}");

        Long logId = service.ingestNew(mail(7, "AVVISI PARTENZA TRENO 4521"), false).orElseThrow();

        assertThat(db.count("mail_processing_log")).isEqualTo(1);
        assertThat(attemptDao.findByLog(logId)).hasSize(2);
        assertThat(processor.count()).isEqualTo(1);
        assertThat(secondo.count()).isEqualTo(1);
        assertThat(logDao.find(logId).orElseThrow().matchedRuleIds()).hasSize(2);
    }

    @Test
    @DisplayName("stop_on_match = 0 e prima regola fallita: la seconda non parte (niente successo parziale)")
    void primoFallimentoAbortaLeRimanenti() {
        db.insertRule("prima", accountId, "test-proc", "AVVISI", 10, false, "{}");
        db.insertRule("seconda", accountId, "secondo-proc", "TRENO", 20, true, "{}");
        processor.behaviour(ctx -> {
            throw new TerminalMailProcessingException("BAD_FORMAT", "rotta", null);
        });

        Long logId = service.ingestNew(mail(7, "AVVISI PARTENZA TRENO 4521"), false).orElseThrow();

        assertThat(db.status(logId)).isEqualTo("DEAD_LETTER");
        assertThat(secondo.count()).isZero();
    }

    @Test
    @DisplayName("il MIME archiviato permette di ricostruire la mail senza toccare la casella")
    void ricostruzioneDaRaw() {
        ruleFor("test-proc", "AVVISI");
        Long logId = service.ingestNew(mail(7, "AVVISI PARTENZA TRENO 4521"), false).orElseThrow();

        ParsedMail rebuilt = service.rebuildFromRaw(logDao.find(logId).orElseThrow()).orElseThrow();

        assertThat(rebuilt.subject()).isEqualTo("AVVISI PARTENZA TRENO 4521");
        assertThat(rebuilt.uid()).isEqualTo(7);
        assertThat(rebuilt.accountId()).isEqualTo(accountId);
    }

    @Test
    @DisplayName("dopo un reset di UIDVALIDITY il Message-ID evita la doppia elaborazione")
    void skipDopoResetUidValidity() {
        ruleFor("test-proc", "AVVISI");
        service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();

        // stessa mail, stessa casella, ma UIDVALIDITY nuova: il claim non protegge piu'
        ParsedMail dopoReset = new ParsedMail(accountId, "casella-test", "INBOX", 99L, 1L,
                "<msg-7@test>", "mario.rossi@ferrovie.it", "Mario Rossi", List.of(), "AVVISI 1",
                clock.instant(), clock.instant(), "corpo", null, List.of(), 100, List.of());
        Long logId = service.ingestNew(new FetchedMail(dopoReset, "raw".getBytes(StandardCharsets.UTF_8)), true)
                .orElseThrow();

        assertThat(db.status(logId)).isEqualTo("SKIPPED");
        assertThat(logDao.find(logId).orElseThrow().message()).contains("Message-ID");
        assertThat(processor.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("il backoff cresce e resta sotto il tetto configurato")
    void backoff() {
        props.getRetry().setBaseSeconds(60);
        props.getRetry().setMaxSeconds(1800);

        assertThat(service.nextRetryAt(1)).isBetween(clock.instant().plusSeconds(54), clock.instant().plusSeconds(66));
        assertThat(service.nextRetryAt(2)).isBetween(clock.instant().plusSeconds(108), clock.instant().plusSeconds(132));
        assertThat(service.nextRetryAt(10)).isBetween(clock.instant().plusSeconds(1620),
                clock.instant().plusSeconds(1980));
    }

    @Test
    @DisplayName("cancellare una casella porta via regole, log, audit e MIME (ON DELETE CASCADE reale)")
    void cascadeDelete() {
        ruleFor("test-proc", "AVVISI");
        service.ingestNew(mail(7, "AVVISI 1"), false).orElseThrow();
        assertThat(db.count("mail_processing_log")).isEqualTo(1);
        assertThat(db.count("mail_processing_attempt")).isEqualTo(1);
        assertThat(db.count("mail_raw")).isEqualTo(1);

        db.jdbc().update("DELETE FROM mail_account WHERE id = ?", accountId);

        assertThat(db.count("mail_rule")).isZero();
        assertThat(db.count("mail_processing_log")).isZero();
        assertThat(db.count("mail_processing_attempt")).isZero();
        assertThat(db.count("mail_raw")).isZero();
    }
}
