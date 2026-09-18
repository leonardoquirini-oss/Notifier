package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.dao.RuleDao;
import it.gruppobernardini.switchmail.dto.FetchedMail;
import it.gruppobernardini.switchmail.model.AttemptStatus;
import it.gruppobernardini.switchmail.model.MatchedRules;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.model.TriggeredBy;
import it.gruppobernardini.switchmail.processor.MailContext;
import it.gruppobernardini.switchmail.processor.MailProcessingException;
import it.gruppobernardini.switchmail.processor.MailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import it.gruppobernardini.switchmail.processor.ProcessorParams;
import it.gruppobernardini.switchmail.processor.TerminalMailProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Claim, match, esecuzione, registrazione. Il cuore della crash-safety.
 *
 * <h2>Ordine "elabora poi logga"</h2>
 * <ol>
 *   <li>La mail e' gia' materializzata e nessun handle IMAP e' aperto.</li>
 *   <li>Claim atomico: una statement, ed e' la decisione di dedup.</li>
 *   <li>Archiviazione del MIME (best effort).</li>
 *   <li>Match regole, risoluzione processore, esecuzione (chiamate HTTP fuori da ogni transazione).</li>
 *   <li>Aggiornamento del log + riga di audit, in una sola transazione.</li>
 * </ol>
 *
 * <p>Un crash tra il 4 e il 5 lascia una riga IN_PROGRESS con l'azione BERLink gia' eseguita: questo
 * e' <b>at-least-once</b>, non exactly-once, e sostenere il contrario sarebbe disonesto. Mitigazioni:
 * X-Idempotency-Key su ogni scrittura (BERLink oggi non lo onora) e recupero dei claim orfani marcato
 * INTERRUPTED, che di default richiede un click umano.
 */
@Service
@Slf4j
public class MailIngestService {

    private final ProcessingLogDao logDao;
    private final RuleDao ruleDao;
    private final RuleMatcher matcher;
    private final MailSubProcessorRegistry registry;
    private final RawMailStore rawMailStore;
    private final BerlinkApiClientFactory clientFactory;
    private final ProcessingRecorder recorder;
    private final AdminNotifier notifier;
    private final MailContentExtractor extractor;
    private final SwitchMailProperties props;
    private final Clock clock;

    public MailIngestService(ProcessingLogDao logDao, RuleDao ruleDao, RuleMatcher matcher,
                             MailSubProcessorRegistry registry, RawMailStore rawMailStore,
                             BerlinkApiClientFactory clientFactory, ProcessingRecorder recorder,
                             AdminNotifier notifier, MailContentExtractor extractor,
                             SwitchMailProperties props, Clock clock) {
        this.logDao = logDao;
        this.ruleDao = ruleDao;
        this.matcher = matcher;
        this.registry = registry;
        this.rawMailStore = rawMailStore;
        this.clientFactory = clientFactory;
        this.recorder = recorder;
        this.notifier = notifier;
        this.extractor = extractor;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Percorso del poll: claim + elaborazione.
     *
     * @param afterUidValidityReset dopo un reset di UIDVALIDITY gli UID ripartono, quindi il claim non
     *                              protegge piu' dai doppioni: si controlla anche il Message-ID
     * @return l'id della riga di log se la mail e' stata presa in carico da noi, vuoto se qualcun
     *         altro l'aveva gia' presa
     */
    public Optional<Long> ingestNew(FetchedMail fetched, boolean afterUidValidityReset) {
        ParsedMail mail = fetched.parsed();

        OptionalLong claimed = logDao.claim(mail, 3);
        if (claimed.isEmpty()) {
            log.debug("Mail {} gia' presa in carico: skip", mail.dedupKey());
            return Optional.empty();
        }
        long logId = claimed.getAsLong();

        rawMailStore.store(logId, fetched.raw());

        if (afterUidValidityReset && skipAfterReset(logId, mail)) {
            return Optional.of(logId);
        }

        execute(logId, mail, TriggeredBy.POLL);
        return Optional.of(logId);
    }

    /**
     * Dopo un reset di UIDVALIDITY nessuna delle due opzioni ingenue va bene: rielaborare tutto rifa'
     * le azioni su BERLink, saltare al nuovo high-water perde mail. Si usa il Message-ID come chiave
     * secondaria e si scarta cio' che e' troppo vecchio, lasciando ogni ramo visibile come riga
     * SKIPPED invece che come doppia azione silenziosa.
     */
    private boolean skipAfterReset(long logId, ParsedMail mail) {
        if (logDao.existsByInternetMessageId(mail.accountId(), mail.internetMessageId(), logId)) {
            recorder.recordFinal(logId, ProcessingStatus.SKIPPED, 0,
                    "Gia' processata prima del reset di UIDVALIDITY (riconosciuta dal Message-ID)",
                    null, null, mail.extractionWarnings(), 0L);
            return true;
        }
        Instant tooOld = clock.instant().minus(props.getRetry().getResetLookbackDays(), ChronoUnit.DAYS);
        Instant received = mail.receivedAt() != null ? mail.receivedAt() : mail.sentAt();
        if (received != null && received.isBefore(tooOld)) {
            recorder.recordFinal(logId, ProcessingStatus.SKIPPED, 0,
                    "UIDVALIDITY_RESET_TOO_OLD: mail anteriore alla finestra di "
                            + props.getRetry().getResetLookbackDays() + " giorni, non rielaborata",
                    null, null, mail.extractionWarnings(), 0L);
            return true;
        }
        return false;
    }

    /**
     * Match + esecuzione + registrazione su una riga gia' claimata. Usato sia dal poll sia dai retry
     * (automatici e manuali), cosi' esiste un solo percorso di esecuzione da mantenere.
     */
    public void execute(long logId, ParsedMail mail, TriggeredBy trigger) {
        ProcessingLogEntry entry = logDao.find(logId).orElseThrow(
                () -> new IllegalStateException("riga di log #" + logId + " sparita durante l'elaborazione"));
        int attempt = entry.attempt() + 1;
        long t0 = System.currentTimeMillis();

        MatchedRules matched = matcher.selectFor(mail, ruleDao.findForEvaluation(mail.accountId()));

        if (matched.isEmpty()) {
            // NO_RULE non e' rumore: e' il feed di scoperta, la lista che dice quali regole scrivere.
            logDao.setBinding(logId, null, null, List.of(), null, entry.maxAttempts());
            recorder.recordFinal(logId, ProcessingStatus.NO_RULE, attempt,
                    "Nessuna regola corrisponde a questa mail. MIME archiviato: usabile per scriverne una.",
                    null, null, mail.extractionWarnings(), System.currentTimeMillis() - t0);
            return;
        }

        RuleConfig first = matched.first();
        logDao.setBinding(logId, first.id(), first.name(), matched.ids(), first.processorId(), first.maxAttempts());

        List<String> messages = new ArrayList<>();
        List<String> extracted = new ArrayList<>();
        String actionRef = null;
        boolean anySuccess = false;

        for (RuleConfig rule : matched.toExecute()) {
            Instant started = clock.instant();
            long ruleT0 = System.currentTimeMillis();
            try {
                ProcessingOutcome outcome = runRule(rule, mail, attempt);
                long durationMs = System.currentTimeMillis() - ruleT0;

                recorder.recordAttempt(logId, attempt, rule.id(), rule.processorId(),
                        outcome.isSuccess() ? AttemptStatus.SUCCESS : AttemptStatus.SKIPPED,
                        outcome.message(), null, null, durationMs, trigger, started, clock.instant());

                messages.add(matched.toExecute().size() > 1 ? rule.label() + ": " + outcome.message()
                                                            : outcome.message());
                if (outcome.isSuccess()) {
                    anySuccess = true;
                    if (outcome.extractedJson() != null) {
                        extracted.add(outcome.extractedJson());
                    }
                    if (outcome.actionRef() != null) {
                        actionRef = outcome.actionRef();
                    }
                }
            } catch (RuntimeException e) {
                // Fail-fast e non successo-parziale: il retry rigira TUTTE le regole, e rigirare una
                // regola gia' riuscita e' sicuro solo se la chiamata a BERLink e' idempotente, cosa
                // che non possiamo assumere.
                handleFailure(logId, attempt, entry.maxAttempts(), rule, e, mail,
                        System.currentTimeMillis() - ruleT0, trigger, started);
                return;
            }
        }

        ProcessingStatus status = anySuccess ? ProcessingStatus.SUCCESS : ProcessingStatus.SKIPPED;
        String extractedJson = extracted.isEmpty() ? null
                : (extracted.size() == 1 ? extracted.get(0) : "[" + String.join(",", extracted) + "]");
        recorder.recordFinal(logId, status, attempt, String.join(" | ", messages), extractedJson, actionRef,
                mail.extractionWarnings(), System.currentTimeMillis() - t0);
    }

    private ProcessingOutcome runRule(RuleConfig rule, ParsedMail mail, int attempt) {
        MailSubProcessor processor = registry.require(rule.processorId());
        ProcessorParams params;
        try {
            params = ProcessorParams.ofJson(rule.paramsJson(), processor.paramSpecs());
            processor.validateRule(rule, params);
        } catch (IllegalArgumentException e) {
            // Config incoerente col codice: ritentarla non la aggiusta, la aggiusta un umano da /rules.
            throw new TerminalMailProcessingException("CONFIG_INVALID",
                    "regola " + rule.label() + " non valida per il processore " + rule.processorId()
                            + ": " + e.getMessage(), e);
        }
        MailContext ctx = new MailContext(mail, rule, params, attempt, false,
                clientFactory.live(mail, rule.id()));
        return processor.process(ctx);
    }

    private void handleFailure(long logId, int attempt, int maxAttempts, RuleConfig rule, RuntimeException e,
                               ParsedMail mail, long durationMs, TriggeredBy trigger, Instant started) {
        boolean retryable = e instanceof MailProcessingException mpe && mpe.retryable();
        String errorType = e instanceof MailProcessingException mpe ? mpe.errorType() : "UNEXPECTED";
        String errorMessage = e.getMessage();

        recorder.recordAttempt(logId, attempt, rule.id(), rule.processorId(),
                retryable ? AttemptStatus.FAILED_RETRYABLE : AttemptStatus.FAILED_TERMINAL,
                null, errorType, errorMessage, durationMs, trigger, started, clock.instant());

        if (retryable && attempt < maxAttempts) {
            Instant next = nextRetryAt(attempt);
            recorder.recordRetry(logId, attempt, next,
                    "Tentativo " + attempt + "/" + maxAttempts + " fallito, nuovo tentativo alle " + next,
                    errorType, errorMessage, stackOf(e), durationMs);
            log.warn("Mail #{} regola {}: {} - retry alle {}", logId, rule.label(), errorType, next);
            return;
        }

        String reason = retryable
                ? "Esauriti i " + maxAttempts + " tentativi"
                : "Errore terminale";
        recorder.recordFailure(logId, ProcessingStatus.DEAD_LETTER, attempt,
                reason + " sulla regola " + rule.label(), mail.extractionWarnings(),
                errorType, errorMessage, stackOf(e), durationMs);
        log.error("Mail #{} in dead-letter ({}): {}", logId, errorType, errorMessage);
        notifier.deadLetter(logId, mail.subject(), errorType, errorMessage);
    }

    /** Backoff esponenziale con tetto e jitter: tre retry ravvicinati non aiutano nessuno. */
    public Instant nextRetryAt(int attempt) {
        long base = props.getRetry().getBaseSeconds();
        long capped = Math.min(base * (1L << Math.max(0, attempt - 1)), props.getRetry().getMaxSeconds());
        double jitter = props.getRetry().getJitterPercent() / 100.0;
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return clock.instant().plus(Duration.ofMillis((long) (capped * 1000 * factor)));
    }

    /** Ricostruisce la mail dal MIME archiviato: e' cosi' che un retry non dipende dalla casella. */
    public Optional<ParsedMail> rebuildFromRaw(ProcessingLogEntry entry) {
        return rawMailStore.load(entry.id()).map(raw -> extractor.extract(raw, entry.accountId(),
                entry.accountName(), entry.folder(), entry.uidValidity(), entry.uid()));
    }

    private static String stackOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() <= 8000 ? s : s.substring(0, 8000) + "\n...";
    }
}
