package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dao.MailFolderStateDao;
import it.gruppobernardini.switchmail.dao.ProcessingLogDao;
import it.gruppobernardini.switchmail.dto.FetchedMail;
import it.gruppobernardini.switchmail.dto.ImapFetchResult;
import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.MailFolderState;
import it.gruppobernardini.switchmail.model.PostAction;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un poll di una casella: configurazione, fetch, ingestione, aggiornamento dello stato.
 *
 * <p>Il high-water mark avanza <b>solo a fine batch</b> e solo fino al massimo UID effettivamente
 * preso in carico. Se il processo muore a meta' batch, last_uid resta indietro, il poll successivo
 * rifa' il fetch di quegli UID e il claim scarta quelli gia' presi: corretto, al costo di una FETCH
 * in piu'.
 */
@Service
@Slf4j
public class MailPollService {

    /** Flag one-shot per casella: al primo poll dopo un reset di UIDVALIDITY si controlla il Message-ID. */
    private final Map<Long, Boolean> resetPending = new ConcurrentHashMap<>();

    private final AccountService accountService;
    private final ImapMailReader reader;
    private final MailIngestService ingestService;
    private final MailFolderStateDao folderStateDao;
    private final ProcessingLogDao logDao;
    private final AdminNotifier notifier;
    private final Clock clock;

    public MailPollService(AccountService accountService, ImapMailReader reader, MailIngestService ingestService,
                           MailFolderStateDao folderStateDao, ProcessingLogDao logDao, AdminNotifier notifier,
                           Clock clock) {
        this.accountService = accountService;
        this.reader = reader;
        this.ingestService = ingestService;
        this.folderStateDao = folderStateDao;
        this.logDao = logDao;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** @param fetched mail scaricate, {@code ingested} quelle prese in carico da noi */
    public record PollReport(long accountId, int fetched, int ingested, boolean uidValidityReset, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    public PollReport pollAccount(long accountId) {
        MailAccount account = accountService.require(accountId);
        if (!account.enabled()) {
            return new PollReport(accountId, 0, 0, false, null);
        }

        try {
            String password = accountService.passwordOf(account);
            Optional<MailFolderState> state = folderStateDao.find(accountId, account.folder());
            boolean initial = state.isEmpty();

            ImapFetchResult result = reader.fetch(account, password,
                    state.map(MailFolderState::uidValidity).orElse(null),
                    state.map(MailFolderState::lastUid).orElse(0L),
                    initial);

            if (result.uidValidityChanged()) {
                return handleUidValidityReset(account, state.orElse(null), result);
            }

            boolean afterReset = Boolean.TRUE.equals(resetPending.remove(accountId));
            List<Long> processedUids = new ArrayList<>();
            List<Long> logIds = new ArrayList<>();
            int ingested = 0;

            for (FetchedMail mail : result.mails()) {
                Optional<Long> logId = ingestService.ingestNew(mail, afterReset);
                processedUids.add(mail.parsed().uid());
                if (logId.isPresent()) {
                    ingested++;
                    logIds.add(logId.get());
                }
            }

            long maxUid = processedUids.stream().mapToLong(Long::longValue).max().orElse(0L);
            if (initial) {
                folderStateDao.upsert(accountId, account.folder(), result.uidValidity(), maxUid);
                log.info("Prima sincronizzazione di {}: {} mail nella finestra di {} giorni, baseline uid={}",
                        account.name(), result.mails().size(), account.initialLookbackDays(), maxUid);
            } else if (maxUid > 0) {
                folderStateDao.advanceLastUid(accountId, account.folder(), maxUid);
            }

            applyPostActions(account, password, logIds);
            accountService.recordPollSuccess(accountId, clock.instant(), result.mails().size());
            return new PollReport(accountId, result.mails().size(), ingested, false, null);

        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Poll fallito su {}: {}", account.name(), message);
            accountService.recordPollFailure(accountId, clock.instant(), message);
            notifier.pollFailure(account.name(), message);
            return new PollReport(accountId, 0, 0, false, message);
        }
    }

    /**
     * Reset di UIDVALIDITY: gli UID della cartella ripartono, quindi il claim non protegge piu'.
     *
     * <p>Non si rielabora tutto (rifarebbe le azioni su BERLink) e non si salta al nuovo high-water
     * (perderebbe mail): si riparte da zero sulla cartella con un flag che, al poll successivo, fa
     * controllare anche il Message-ID. E' best-effort, ma ogni ramo e' loggato e consultabile.
     */
    private PollReport handleUidValidityReset(MailAccount account, MailFolderState previous, ImapFetchResult result) {
        long oldValue = previous == null ? 0 : previous.uidValidity();
        folderStateDao.upsert(account.id(), account.folder(), result.uidValidity(), 0L);
        resetPending.put(account.id(), Boolean.TRUE);
        notifier.uidValidityReset(account.name(), account.folder(), oldValue, result.uidValidity());
        accountService.recordPollSuccess(account.id(), clock.instant(), 0);
        return new PollReport(account.id(), 0, 0, true, null);
    }

    /** Solo su casella posseduta, e solo per le mail arrivate a un esito definitivo. */
    private void applyPostActions(MailAccount account, String password, List<Long> logIds) {
        if (account.accessMode() != AccessMode.OWNED || account.postAction() == PostAction.NONE
                || logIds.isEmpty()) {
            return;
        }
        List<Long> uids = new ArrayList<>();
        for (Long logId : logIds) {
            logDao.find(logId)
                    .filter(e -> e.status() == ProcessingStatus.SUCCESS || e.status() == ProcessingStatus.SKIPPED
                            || e.status() == ProcessingStatus.NO_RULE)
                    .ifPresent(e -> uids.add(e.uid()));
        }
        reader.applyPostActions(account, password, uids);
    }

    /** Usato dai test e dal bottone di poll manuale. */
    public boolean isResetPending(long accountId) {
        return Boolean.TRUE.equals(resetPending.get(accountId));
    }
}
