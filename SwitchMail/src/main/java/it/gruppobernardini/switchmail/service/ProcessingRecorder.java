package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dao.ProcessingAttemptDao;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.model.AttemptStatus;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.model.TriggeredBy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scrive l'esito: riga di log aggiornata + riga di audit, <b>in una sola transazione</b>.
 *
 * <p>Sta in un componente a se' perche' @Transactional non si applica alle chiamate interne a una
 * classe: se questi metodi vivessero dentro MailIngestService verrebbero invocati senza proxy e la
 * transazione non esisterebbe. Qui invece log e attempt non possono divergere.
 *
 * <p>La chiamata HTTP a BERLink resta deliberatamente fuori da qualsiasi transazione: un POST non e'
 * transazionale, e tenere l'unico writer SQLite per dieci secondi di rete bloccherebbe la UI e ogni
 * altro poll.
 */
@Service
public class ProcessingRecorder {

    private final ProcessingLogDao logDao;
    private final ProcessingAttemptDao attemptDao;

    public ProcessingRecorder(ProcessingLogDao logDao, ProcessingAttemptDao attemptDao) {
        this.logDao = logDao;
        this.attemptDao = attemptDao;
    }

    @Transactional
    public void recordFinal(long logId, ProcessingStatus status, int attempt, String message,
                            String extractedJson, String actionRef, List<String> warnings, Long durationMs) {
        logDao.recordTerminalState(logId, status, attempt, message, extractedJson, actionRef, warnings,
                null, null, null, durationMs);
    }

    @Transactional
    public void recordAttempt(long logId, int attempt, Long ruleId, String processorId, AttemptStatus status,
                              String message, String errorType, String errorMessage, Long durationMs,
                              TriggeredBy trigger, Instant startedAt, Instant finishedAt) {
        attemptDao.insert(logId, attempt, ruleId, processorId, status, message, errorType, errorMessage,
                durationMs, trigger, startedAt, finishedAt);
    }

    @Transactional
    public void recordFailure(long logId, ProcessingStatus status, int attempt, String message,
                              List<String> warnings, String errorType, String errorMessage, String errorStack,
                              Long durationMs) {
        logDao.recordTerminalState(logId, status, attempt, message, null, null, warnings,
                errorType, errorMessage, errorStack, durationMs);
    }

    @Transactional
    public void recordRetry(long logId, int attempt, Instant nextRetryAt, String message, String errorType,
                            String errorMessage, String errorStack, Long durationMs) {
        logDao.scheduleRetry(logId, attempt, nextRetryAt, message, errorType, errorMessage, errorStack, durationMs);
    }
}
