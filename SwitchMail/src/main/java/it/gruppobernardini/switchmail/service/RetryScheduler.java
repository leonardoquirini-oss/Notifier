package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.model.AttemptStatus;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.model.TriggeredBy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Riprova quello che si puo' riprovare e recupera i claim orfani.
 *
 * <p>Gira anche come ApplicationRunner all'avvio: se il processo e' stato ucciso (SIGKILL, OOM, host
 * spento) restano righe IN_PROGRESS che nessuno riprendera' mai, e vanno intercettate subito.
 */
@Component
@Slf4j
public class RetryScheduler implements ApplicationRunner {

    private static final int BATCH = 50;

    private final ProcessingLogDao logDao;
    private final MailIngestService ingestService;
    private final ProcessingRecorder recorder;
    private final AdminNotifier notifier;
    private final SwitchMailProperties props;
    private final Clock clock;

    public RetryScheduler(ProcessingLogDao logDao, MailIngestService ingestService, ProcessingRecorder recorder,
                          AdminNotifier notifier, SwitchMailProperties props, Clock clock) {
        this.logDao = logDao;
        this.ingestService = ingestService;
        this.recorder = recorder;
        this.notifier = notifier;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = recoverStaleClaims();
        if (recovered > 0) {
            log.warn("All'avvio sono stati recuperati {} claim interrotti", recovered);
        }
    }

    @Scheduled(cron = "${switchmail.retry.sweep-cron}")
    public void sweep() {
        recoverStaleClaims();
        runDueRetries();
    }

    /** Le mail con un retry gia' scaduto. */
    public int runDueRetries() {
        List<ProcessingLogEntry> due = logDao.findDueRetries(clock.instant(), BATCH);
        int done = 0;
        for (ProcessingLogEntry entry : due) {
            if (retry(entry, TriggeredBy.RETRY_AUTO)) {
                done++;
            }
        }
        if (done > 0) {
            log.info("Retry automatici eseguiti: {}", done);
        }
        return done;
    }

    /**
     * Rielabora una riga a partire dal MIME archiviato. Vale anche per SKIPPED e NO_RULE, cosi' una
     * regola appena scritta si puo' applicare a una mail vecchia: e' questo il ciclo che rende
     * iterativa la scrittura delle regole.
     */
    public boolean retry(ProcessingLogEntry entry, TriggeredBy trigger) {
        Optional<ParsedMail> mail = ingestService.rebuildFromRaw(entry);
        if (mail.isEmpty()) {
            recorder.recordFailure(entry.id(), ProcessingStatus.DEAD_LETTER, entry.attempt(),
                    "Retry impossibile: il MIME non e' piu' archiviato (retention o archiviazione disattivata)",
                    entry.warnings(), "RAW_MISSING", null, null, 0L);
            log.warn("Retry impossibile per il log #{}: MIME assente", entry.id());
            return false;
        }
        logDao.reclaim(entry.id());
        ingestService.execute(entry.id(), mail.get(), trigger);
        return true;
    }

    /**
     * Claim orfani: righe IN_PROGRESS piu' vecchie del timeout.
     *
     * <p>Il messaggio dice esplicitamente che l'azione su BERLink potrebbe essere gia' stata
     * eseguita, e con {@code auto-retry-interrupted = false} (default) la riga va in dead-letter e
     * aspetta un umano: una scrittura potenzialmente duplicata e' una decisione, non un
     * comportamento automatico.
     */
    public int recoverStaleClaims() {
        var threshold = clock.instant().minus(props.getRetry().getStaleClaimTimeoutMinutes(), ChronoUnit.MINUTES);
        List<ProcessingLogEntry> stale = logDao.findStaleClaims(threshold, BATCH);
        for (ProcessingLogEntry entry : stale) {
            String message = "Elaborazione interrotta a meta': l'azione su BERLink potrebbe essere gia' "
                    + "stata eseguita - verificare prima di riprovare";
            recorder.recordAttempt(entry.id(), Math.max(1, entry.attempt()), entry.ruleId(), entry.processorId(),
                    AttemptStatus.FAILED_TERMINAL, message, "INTERRUPTED", null, null,
                    TriggeredBy.RETRY_AUTO, entry.claimedAt(), clock.instant());

            if (props.getRetry().isAutoRetryInterrupted()) {
                recorder.recordRetry(entry.id(), entry.attempt(), clock.instant(), message,
                        "INTERRUPTED", null, null, null);
                log.warn("Claim interrotto sul log #{}: rimesso in coda (auto-retry-interrupted = true)", entry.id());
            } else {
                recorder.recordFailure(entry.id(), ProcessingStatus.DEAD_LETTER, entry.attempt(), message,
                        entry.warnings(), "INTERRUPTED", null, null, null);
                notifier.deadLetter(entry.id(), entry.mailSubject(), "INTERRUPTED", message);
                log.error("Claim interrotto sul log #{}: in dead-letter, richiede verifica umana", entry.id());
            }
        }
        return stale.size();
    }
}
