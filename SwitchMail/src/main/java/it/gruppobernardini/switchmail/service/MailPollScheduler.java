package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.model.MailAccount;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Il tick: decide quali caselle sono scadute e le manda in poll. Nessuna logica di dominio.
 *
 * <p>La guardia anti-overlap e' <b>per casella</b> e non globale: un host Exchange irraggiungibile,
 * bloccato su un read timeout di 30 secondi, non deve affamare le altre caselle. Il pool di
 * esecuzione e' limitato a {@code switchmail.poll.max-concurrent-accounts}, cosi' N caselle non
 * aprono N connessioni IMAP insieme.
 */
@Component
@Slf4j
public class MailPollScheduler {

    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final ExecutorService pool;

    private final AccountService accountService;
    private final MailPollService pollService;
    private final SwitchMailProperties props;
    private final Clock clock;

    public MailPollScheduler(AccountService accountService, MailPollService pollService,
                             SwitchMailProperties props, Clock clock) {
        this.accountService = accountService;
        this.pollService = pollService;
        this.props = props;
        this.clock = clock;
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.getPoll().getMaxConcurrentAccounts()),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("sm-poll-" + t.getId());
                    t.setDaemon(false);
                    return t;
                });
    }

    @Scheduled(cron = "${switchmail.poll.tick-cron}")
    public void tick() {
        if (!props.getPoll().isEnabled()) {
            return;
        }
        for (MailAccount account : accountService.findEnabled()) {
            if (!isDue(account)) {
                continue;
            }
            submit(account.id());
        }
    }

    /** Poll immediato di una casella, usato dal bottone della UI e dai test. */
    public boolean submit(long accountId) {
        AtomicBoolean guard = running.computeIfAbsent(accountId, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.debug("Poll della casella #{} gia' in corso: saltato", accountId);
            return false;
        }
        pool.submit(() -> {
            try {
                pollService.pollAccount(accountId);
            } catch (Exception e) {
                log.error("Poll della casella #{} terminato con errore non gestito", accountId, e);
            } finally {
                guard.set(false);
            }
        });
        return true;
    }

    /**
     * La cadenza e' per casella (mail_account.poll_cron): il tick globale e' solo il battito che la
     * valuta. Nessun ultimo poll registrato = scaduta, cosi' una casella appena creata parte subito.
     */
    boolean isDue(MailAccount account) {
        if (account.lastPollAt() == null) {
            return true;
        }
        try {
            CronExpression cron = CronExpression.parse(account.pollCron());
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(account.lastPollAt(), zone));
            return next == null || !next.isAfter(ZonedDateTime.ofInstant(clock.instant(), zone));
        } catch (Exception e) {
            log.error("Cron non valido sulla casella {} ('{}'): poll saltato finche' non viene corretto",
                    account.name(), account.pollCron());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Poll ancora in corso allo shutdown: interrotti");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
