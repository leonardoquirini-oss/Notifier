package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.dao.RawMailDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Pulizia periodica.
 *
 * <p>Il MIME grezzo scade prima del log: e' la parte pesante, e dopo qualche settimana il suo valore
 * (essere una fixture di test) e' gia' stato raccolto. Le righe di dead-letter non vengono mai
 * cancellate d'ufficio: restano finche' qualcuno non le risolve.
 */
@Service
@Slf4j
public class RetentionService {

    private final RawMailDao rawMailDao;
    private final ProcessingLogDao logDao;
    private final SwitchMailProperties props;
    private final Clock clock;

    public RetentionService(RawMailDao rawMailDao, ProcessingLogDao logDao, SwitchMailProperties props,
                            Clock clock) {
        this.rawMailDao = rawMailDao;
        this.logDao = logDao;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        int raw = rawMailDao.deleteOlderThan(
                clock.instant().minus(props.getMail().getRawRetentionDays(), ChronoUnit.DAYS));
        int logs = logDao.deleteResolvedOlderThan(
                clock.instant().minus(props.getMail().getLogRetentionDays(), ChronoUnit.DAYS));
        if (raw > 0 || logs > 0) {
            log.info("Retention: rimossi {} MIME archiviati e {} righe di log risolte", raw, logs);
        }
    }
}
