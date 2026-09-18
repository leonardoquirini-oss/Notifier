package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dao.ProcessingAttemptDao;
import it.gruppobernardini.switchmail.dao.MaintenanceDao;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.dao.RawMailDao;
import it.gruppobernardini.switchmail.dto.FetchedMail;
import it.gruppobernardini.switchmail.dto.LogFilter;
import it.gruppobernardini.switchmail.dto.LogPage;
import it.gruppobernardini.switchmail.dto.StorageStats;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.model.TriggeredBy;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cosa serve alla pagina /logs: elenco, dettaglio, riprova, segna risolta, .eml, "vedi parsed".
 *
 * <p>La Riprova vale anche per SKIPPED e NO_RULE: e' cosi' che una regola appena scritta si applica a
 * una mail vecchia, ed e' il ciclo che rende iterativa la scrittura delle regole.
 */
@Service
@Slf4j
public class ProcessingLogService {

    private final ProcessingLogDao logDao;
    private final ProcessingAttemptDao attemptDao;
    private final RawMailDao rawMailDao;
    private final MaintenanceDao maintenanceDao;
    private final RawMailStore rawMailStore;
    private final MailIngestService ingestService;
    private final RetryScheduler retryScheduler;
    private final AccountService accountService;
    private final ImapMailReader reader;
    private final Clock clock;

    public ProcessingLogService(ProcessingLogDao logDao, ProcessingAttemptDao attemptDao, RawMailDao rawMailDao,
                                MaintenanceDao maintenanceDao, RawMailStore rawMailStore,
                                MailIngestService ingestService, RetryScheduler retryScheduler,
                                AccountService accountService, ImapMailReader reader, Clock clock) {
        this.logDao = logDao;
        this.attemptDao = attemptDao;
        this.rawMailDao = rawMailDao;
        this.maintenanceDao = maintenanceDao;
        this.rawMailStore = rawMailStore;
        this.ingestService = ingestService;
        this.retryScheduler = retryScheduler;
        this.accountService = accountService;
        this.reader = reader;
        this.clock = clock;
    }

    public LogPage page(LogFilter filter) {
        List<ProcessingLogEntry> rows = logDao.page(filter);
        if (rows.isEmpty()) {
            return new LogPage(rows, null, null);
        }
        ProcessingLogEntry last = rows.get(rows.size() - 1);
        boolean full = rows.size() >= filter.limit();
        return new LogPage(rows,
                full ? TimestampUtil.format(last.createdAt()) : null,
                full ? last.id() : null);
    }

    public Map<String, Integer> counts() {
        return logDao.countsByStatus();
    }

    public ProcessingLogEntry require(long id) {
        return logDao.find(id).orElseThrow(() -> new IllegalArgumentException("riga di log inesistente: #" + id));
    }

    public List<Map<String, Object>> attempts(long logId) {
        return attemptDao.findByLog(logId);
    }

    /**
     * Riprova manuale. Prima il MIME archiviato; se non c'e' piu', si tenta il re-fetch per UID dalla
     * casella, che funziona finche' la mail non e' stata cancellata.
     */
    public void retry(long id) {
        ProcessingLogEntry entry = require(id);
        if (rawMailStore.load(id).isPresent()) {
            retryScheduler.retry(entry, TriggeredBy.RETRY_MANUAL);
            return;
        }
        Optional<ParsedMail> refetched = refetch(entry);
        if (refetched.isEmpty()) {
            throw new IllegalArgumentException(
                    "MIME non archiviato e mail non piu' presente in casella: impossibile riprovare");
        }
        logDao.reclaim(id);
        ingestService.execute(id, refetched.get(), TriggeredBy.RETRY_MANUAL);
    }

    public int retryAll(List<Long> ids) {
        int done = 0;
        for (Long id : ids) {
            try {
                retry(id);
                done++;
            } catch (RuntimeException e) {
                log.warn("Riprova fallita per il log #{}: {}", id, e.getMessage());
            }
        }
        return done;
    }

    /** Svuota la dead-letter senza fingere che la mail sia riuscita. */
    public void resolve(long id, String note) {
        require(id);
        logDao.markResolved(id, note);
    }

    /** Il .eml da mettere in src/test/resources/mail/ come fixture. */
    public Optional<byte[]> eml(long id) {
        require(id);
        return rawMailStore.load(id);
    }

    /** Cosa ha visto l'extractor: risponde a "il parser ha visto l'allegato?" senza debugger. */
    public Optional<ParsedMail> parsedView(long id) {
        return ingestService.rebuildFromRaw(require(id));
    }

    // ------------------------------------------------------------------ pulizia dell'archivio

    /**
     * Elimina il MIME archiviato e lascia il log.
     *
     * <p>E' la pulizia senza controindicazioni: la riga resta consultabile, il registro di dedup
     * intatto, e sparisce il peso vero (i blob). Si perde solo la possibilita' di scaricare l'.eml
     * e di rielaborare quella mail senza ripescarla dalla casella.
     */
    public int purgeRaw(List<Long> ids) {
        int removed = rawMailDao.deleteByLogIds(ids);
        log.info("Eliminati {} MIME archiviati", removed);
        return removed;
    }

    /**
     * Elimina le righe di log (e con loro tentativi e MIME).
     *
     * <p><b>Toglie anche la memoria di dedup</b> di quelle mail: finche' il high-water mark degli
     * UID resta, non verranno riscaricate, ma se la cartella cambiasse UIDVALIDITY il controllo di
     * sicurezza sul Message-ID non le riconoscerebbe piu' e verrebbero rielaborate. Le righe
     * IN_PROGRESS non vengono toccate.
     */
    public int deleteRows(List<Long> ids) {
        int removed = logDao.deleteByIds(ids);
        log.warn("Eliminate {} righe di log su {} richieste", removed, ids == null ? 0 : ids.size());
        return removed;
    }

    /** Quante righe sparirebbero con questi criteri, senza toccare niente. */
    public int countPurgeable(List<ProcessingStatus> statuses, Integer olderThanDays, Long accountId) {
        return logDao.purge(statuses, threshold(olderThanDays), accountId, true);
    }

    public int purge(List<ProcessingStatus> statuses, Integer olderThanDays, Long accountId) {
        int removed = logDao.purge(statuses, threshold(olderThanDays), accountId, false);
        log.warn("Pulizia archivio: eliminate {} righe (stati={}, piu' vecchie di {} giorni, casella={})",
                removed, statuses, olderThanDays, accountId);
        return removed;
    }

    private Instant threshold(Integer olderThanDays) {
        return olderThanDays == null || olderThanDays <= 0
                ? null
                : clock.instant().minus(olderThanDays, ChronoUnit.DAYS);
    }

    public StorageStats storage() {
        return maintenanceDao.stats();
    }

    /** Restituisce al filesystem lo spazio delle righe cancellate. */
    public StorageStats compact() {
        maintenanceDao.vacuum();
        return maintenanceDao.stats();
    }

    private Optional<ParsedMail> refetch(ProcessingLogEntry entry) {
        Optional<MailAccount> account = accountService.find(entry.accountId());
        if (account.isEmpty()) {
            return Optional.empty();
        }
        try {
            String password = accountService.passwordOf(account.get());
            return reader.fetchByUid(account.get(), password, entry.uidValidity(), entry.uid())
                    .map(FetchedMail::parsed);
        } catch (RuntimeException e) {
            log.warn("Re-fetch del log #{} non riuscito: {}", entry.id(), e.getMessage());
            return Optional.empty();
        }
    }
}
