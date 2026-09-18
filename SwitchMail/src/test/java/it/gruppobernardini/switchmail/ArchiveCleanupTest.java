package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.dao.MaintenanceDao;
import it.gruppobernardini.switchmail.dao.ProcessingAttemptDao;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.dao.RawMailDao;
import it.gruppobernardini.switchmail.model.AttemptStatus;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.model.TriggeredBy;
import it.gruppobernardini.switchmail.support.MutableClock;
import it.gruppobernardini.switchmail.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pulizia dell'archivio: due operazioni con conseguenze diverse, e la differenza va difesa da un
 * test perche' e' invisibile guardando la UI.
 */
class ArchiveCleanupTest {

    private TestDb db;
    private MutableClock clock;
    private ProcessingLogDao logDao;
    private ProcessingAttemptDao attemptDao;
    private RawMailDao rawMailDao;
    private MaintenanceDao maintenanceDao;
    private long accountId;

    @BeforeEach
    void setUp() {
        db = new TestDb();
        clock = new MutableClock(Instant.parse("2026-09-18T08:00:00Z"));
        logDao = new ProcessingLogDao(db.jdbc(), clock);
        attemptDao = new ProcessingAttemptDao(db.jdbc());
        rawMailDao = new RawMailDao(db.jdbc(), clock);
        maintenanceDao = new MaintenanceDao(db.jdbc());
        accountId = db.insertAccount("casella-test");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    /** Una mail presa in carico, con tentativo e MIME archiviato. */
    private long ingest(long uid, ProcessingStatus status) {
        ParsedMail mail = new ParsedMail(accountId, "casella-test", "INBOX", 42L, uid,
                "<msg-" + uid + "@test>", "a@ferrovie.it", "A", List.of(), "oggetto " + uid,
                clock.instant(), clock.instant(), "corpo", null, List.of(), 100, List.of());
        long id = logDao.claim(mail, 3).orElseThrow();
        rawMailDao.save(id, ("MIME della mail " + uid).getBytes(StandardCharsets.UTF_8), 500);
        attemptDao.insert(id, 1, null, "p", AttemptStatus.SUCCESS, "ok", null, null, 5L,
                TriggeredBy.POLL, clock.instant(), clock.instant());
        if (status != ProcessingStatus.IN_PROGRESS) {
            logDao.recordTerminalState(id, status, 1, "fatto", null, null, List.of(), null, null, null, 5L);
        }
        return id;
    }

    @Test
    @DisplayName("eliminare solo il MIME: spariscono i blob, restano log, tentativi e dedup")
    void purgeRaw() {
        long primo = ingest(1, ProcessingStatus.SUCCESS);
        long secondo = ingest(2, ProcessingStatus.SUCCESS);

        int removed = rawMailDao.deleteByLogIds(List.of(primo));

        assertThat(removed).isEqualTo(1);
        assertThat(rawMailDao.exists(primo)).isFalse();
        assertThat(rawMailDao.exists(secondo)).isTrue();
        assertThat(db.count("mail_processing_log")).isEqualTo(2);
        assertThat(db.count("mail_processing_attempt")).isEqualTo(2);

        // la memoria di dedup resta: la stessa mail non verrebbe rielaborata
        ParsedMail stessaMail = new ParsedMail(accountId, "casella-test", "INBOX", 42L, 1L,
                "<msg-1@test>", "a@ferrovie.it", "A", List.of(), "oggetto 1",
                clock.instant(), clock.instant(), "corpo", null, List.of(), 100, List.of());
        assertThat(logDao.claim(stessaMail, 3)).isEmpty();
    }

    @Test
    @DisplayName("eliminare le righe: via anche tentativi e MIME (cascade), e la dedup si perde")
    void deleteRows() {
        long primo = ingest(1, ProcessingStatus.SUCCESS);
        ingest(2, ProcessingStatus.SUCCESS);

        int removed = logDao.deleteByIds(List.of(primo));

        assertThat(removed).isEqualTo(1);
        assertThat(db.count("mail_processing_log")).isEqualTo(1);
        assertThat(db.count("mail_processing_attempt")).isEqualTo(1);
        assertThat(db.count("mail_raw")).isEqualTo(1);

        // senza la riga, la stessa mail verrebbe ripresa in carico: e' il prezzo dichiarato
        ParsedMail stessaMail = new ParsedMail(accountId, "casella-test", "INBOX", 42L, 1L,
                "<msg-1@test>", "a@ferrovie.it", "A", List.of(), "oggetto 1",
                clock.instant(), clock.instant(), "corpo", null, List.of(), 100, List.of());
        assertThat(logDao.claim(stessaMail, 3)).isPresent();
    }

    @Test
    @DisplayName("le mail in elaborazione non si cancellano, ne' per id ne' per criteri")
    void inProgressProtette() {
        long inCorso = ingest(1, ProcessingStatus.IN_PROGRESS);

        assertThat(logDao.deleteByIds(List.of(inCorso))).isZero();
        assertThat(logDao.purge(List.of(), null, null, false)).isZero();
        assertThat(db.count("mail_processing_log")).isEqualTo(1);
    }

    @Test
    @DisplayName("dry-run: conta e non tocca niente")
    void dryRun() {
        ingest(1, ProcessingStatus.SUCCESS);
        ingest(2, ProcessingStatus.NO_RULE);

        int matching = logDao.purge(List.of(ProcessingStatus.SUCCESS), null, null, true);

        assertThat(matching).isEqualTo(1);
        assertThat(db.count("mail_processing_log")).isEqualTo(2);
    }

    @Test
    @DisplayName("pulizia per eta': tocca solo cio' che e' piu' vecchio della soglia")
    void purgePerEta() {
        long vecchia = ingest(1, ProcessingStatus.SUCCESS);
        clock.advance(Duration.ofDays(40));
        long recente = ingest(2, ProcessingStatus.SUCCESS);

        int removed = logDao.purge(List.of(), clock.instant().minus(Duration.ofDays(30)), null, false);

        assertThat(removed).isEqualTo(1);
        assertThat(logDao.find(vecchia)).isEmpty();
        assertThat(logDao.find(recente)).isPresent();
    }

    @Test
    @DisplayName("pulizia per stato e per casella")
    void purgePerStatoECasella() {
        long altraCasella = db.insertAccount("altra");
        ingest(1, ProcessingStatus.SUCCESS);
        ingest(2, ProcessingStatus.NO_RULE);

        assertThat(logDao.purge(List.of(ProcessingStatus.NO_RULE), null, altraCasella, false)).isZero();
        assertThat(logDao.purge(List.of(ProcessingStatus.NO_RULE), null, accountId, false)).isEqualTo(1);
        assertThat(db.count("mail_processing_log")).isEqualTo(1);
    }

    @Test
    @DisplayName("statistiche e compattazione: il file non si rimpicciolisce da solo")
    void statisticheECompattazione() {
        for (int uid = 1; uid <= 20; uid++) {
            ingest(uid, ProcessingStatus.SUCCESS);
        }

        var before = maintenanceDao.stats();
        assertThat(before.logRows()).isEqualTo(20);
        assertThat(before.rawRows()).isEqualTo(20);
        assertThat(before.rawBytes()).isPositive();
        assertThat(before.rawOriginalBytes()).isEqualTo(20 * 500L);
        assertThat(before.dbSizeBytes()).isPositive();

        logDao.purge(List.of(), null, null, false);
        var afterDelete = maintenanceDao.stats();
        assertThat(afterDelete.logRows()).isZero();
        assertThat(afterDelete.rawRows()).isZero();

        maintenanceDao.vacuum();
        var afterVacuum = maintenanceDao.stats();
        assertThat(afterVacuum.reclaimableBytes()).isZero();
        assertThat(afterVacuum.dbSizeBytes()).isLessThanOrEqualTo(afterDelete.dbSizeBytes());
    }
}
