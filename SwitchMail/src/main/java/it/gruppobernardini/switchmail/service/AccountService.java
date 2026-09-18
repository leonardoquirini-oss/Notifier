package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dao.MailAccountDao;
import it.gruppobernardini.switchmail.dto.FolderInfo;
import it.gruppobernardini.switchmail.dto.MailPreview;
import it.gruppobernardini.switchmail.dto.TestConnectionResult;
import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.PostAction;
import it.gruppobernardini.switchmail.util.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * CRUD delle caselle + test di connessione.
 *
 * <p>La password ha un contratto <b>write-only</b>: entra da qui cifrata, esce solo verso il reader
 * IMAP, e alla UI arriva soltanto {@code passwordSet}. L'aggiornamento generico non la tocca, cosi'
 * un round-trip della schermata non puo' svuotarla.
 */
@Service
@Slf4j
public class AccountService {

    private final MailAccountDao dao;
    private final CredentialCipher cipher;
    private final ImapMailReader reader;

    public AccountService(MailAccountDao dao, CredentialCipher cipher, ImapMailReader reader) {
        this.dao = dao;
        this.cipher = cipher;
        this.reader = reader;
    }

    public List<MailAccount> findAll() {
        return dao.findAll();
    }

    public List<MailAccount> findEnabled() {
        return dao.findEnabled();
    }

    public Optional<MailAccount> find(long id) {
        return dao.find(id);
    }

    public MailAccount require(long id) {
        return dao.find(id).orElseThrow(() -> new IllegalArgumentException("casella inesistente: #" + id));
    }

    public long create(MailAccount account, String plainPassword) {
        validate(account);
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new IllegalArgumentException("la password e' obbligatoria alla creazione della casella");
        }
        byte[] encrypted = cipher.encrypt(plainPassword);
        MailAccount withPassword = new MailAccount(null, account.name(), account.host(), account.port(),
                account.useSsl(), account.startTls(), account.trustAllCerts(), account.username(), encrypted,
                account.folder(), account.accessMode(), account.postAction(), account.postActionFolder(),
                account.pollCron(), account.maxMessagesPerPoll(), account.initialLookbackDays(),
                account.connectTimeoutMs(), account.readTimeoutMs(), account.enabled(),
                null, null, null, null, 0, null, null);
        return dao.insert(withPassword);
    }

    public void update(MailAccount account) {
        validate(account);
        require(account.id());
        dao.update(account);
    }

    public void setPassword(long id, String plainPassword) {
        require(id);
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new IllegalArgumentException("password vuota");
        }
        dao.updatePassword(id, cipher.encrypt(plainPassword));
    }

    public void delete(long id) {
        require(id);
        // Regole, log, attempt e MIME della casella se ne vanno con lei: lo fa ON DELETE CASCADE,
        // che qui e' affidabile perche' PRAGMA foreign_keys e' verificata all'avvio.
        dao.delete(id);
    }

    /** L'unico punto in cui una password torna in chiaro, e va solo dentro il reader IMAP. */
    public String passwordOf(MailAccount account) {
        if (!account.passwordSet()) {
            throw new IllegalStateException("nessuna password impostata per la casella " + account.name());
        }
        return cipher.decrypt(account.passwordEncrypted());
    }

    /** Test con la password memorizzata. */
    public TestConnectionResult test(long id, int previewCount) {
        MailAccount account = require(id);
        return reader.testConnection(account, passwordOf(account), previewCount);
    }

    /** Test "prima di salvare": credenziali ad hoc, niente scritture. */
    public TestConnectionResult test(MailAccount draft, String plainPassword, int previewCount) {
        validate(draft);
        String password = plainPassword;
        if ((password == null || password.isBlank()) && draft.id() != null) {
            password = passwordOf(require(draft.id()));
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("serve una password per testare la connessione");
        }
        return reader.testConnection(draft, password, previewCount);
    }

    /** Cartelle visibili con le credenziali salvate. */
    public List<FolderInfo> folders(long id) {
        MailAccount account = require(id);
        return reader.listFolders(account, passwordOf(account));
    }

    /**
     * Cartelle visibili con credenziali ad hoc, prima di salvare: e' il momento in cui serve, perche'
     * il nome della cartella condivisa lo si scopre proprio mentre si configura la casella.
     */
    public List<FolderInfo> folders(MailAccount draft, String plainPassword) {
        validate(draft);
        String password = plainPassword;
        if ((password == null || password.isBlank()) && draft.id() != null) {
            password = passwordOf(require(draft.id()));
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("serve una password per elencare le cartelle");
        }
        return reader.listFolders(draft, password);
    }

    public List<MailPreview> preview(long id, int n) {
        MailAccount account = require(id);
        return reader.preview(account, passwordOf(account), n);
    }

    public void recordPollSuccess(long id, java.time.Instant at, int fetched) {
        dao.recordPollSuccess(id, at, fetched);
    }

    public void recordPollFailure(long id, java.time.Instant at, String error) {
        dao.recordPollFailure(id, at, error);
    }

    public int countRules(long accountId) {
        return dao.countRules(accountId);
    }

    /**
     * Validazioni applicative. Rispecchiano i CHECK dello schema invece di sostituirli: il DB resta
     * l'ultima parola, ma l'operatore riceve un messaggio leggibile invece di un errore SQL.
     */
    void validate(MailAccount a) {
        if (isBlank(a.name())) {
            throw new IllegalArgumentException("il nome della casella e' obbligatorio");
        }
        if (isBlank(a.host())) {
            throw new IllegalArgumentException("l'host IMAP e' obbligatorio");
        }
        if (isBlank(a.username())) {
            throw new IllegalArgumentException("lo username e' obbligatorio");
        }
        if (a.port() < 1 || a.port() > 65535) {
            throw new IllegalArgumentException("porta non valida: " + a.port());
        }
        if (isBlank(a.folder())) {
            throw new IllegalArgumentException("la cartella e' obbligatoria (di norma INBOX)");
        }
        if (a.accessMode() == AccessMode.READ_ONLY && a.postAction() != PostAction.NONE) {
            throw new IllegalArgumentException(
                    "una casella READ_ONLY non puo' avere azioni post-elaborazione: e' la casella di "
                            + "qualcun altro e deve restare intatta");
        }
        if (a.postAction() == PostAction.MOVE && isBlank(a.postActionFolder())) {
            throw new IllegalArgumentException("l'azione MOVE richiede la cartella di destinazione");
        }
        if (a.maxMessagesPerPoll() < 1) {
            throw new IllegalArgumentException("il numero massimo di messaggi per poll deve essere almeno 1");
        }
        if (a.initialLookbackDays() < 0) {
            throw new IllegalArgumentException("i giorni di lookback iniziale non possono essere negativi");
        }
        try {
            CronExpression.parse(a.pollCron());
        } catch (Exception e) {
            throw new IllegalArgumentException("espressione cron non valida: " + a.pollCron()
                    + " (formato Spring a 6 campi, es. \"0 */2 * * * *\")");
        }
        if (a.trustAllCerts()) {
            log.warn("Casella {}: trust_all_certs attivo, la verifica del certificato e' disattivata", a.name());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
