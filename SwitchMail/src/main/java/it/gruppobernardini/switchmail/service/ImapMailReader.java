package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dto.FetchedMail;
import it.gruppobernardini.switchmail.dto.ImapFetchResult;
import it.gruppobernardini.switchmail.dto.MailPreview;
import it.gruppobernardini.switchmail.dto.TestConnectionResult;
import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.PostAction;
import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Tutto jakarta.mail sta qui: connette, apre, fetcha, <b>materializza</b> in {@link ParsedMail} e
 * chiude. Non fa mai uscire una Message.
 *
 * <h2>Le quattro garanzie indipendenti che \Seen non venga settato</h2>
 * <ol>
 *   <li>{@code folder.open(Folder.READ_ONLY)} emette EXAMINE, non SELECT.</li>
 *   <li><b>{@code mail.imaps.peek = true}</b> - quella che morde davvero. getContent() di
 *       jakarta.mail emette normalmente {@code FETCH BODY[...]}, che setta \Seen lato server
 *       <i>anche su una folder che credi read-only</i>. peek forza {@code BODY.PEEK[...]}.
 *       Impostata sempre, in entrambe le modalita'.</li>
 *   <li>Assert dopo l'apertura: se l'account e' READ_ONLY e il server ha aperto in altro modo,
 *       il poll aborta invece di rischiare.</li>
 *   <li>{@code folder.close(false)} in un solo punto, in un finally, con false hardcoded.</li>
 * </ol>
 * Piu' il CHECK sullo schema che rende impossibile una post-action su un account READ_ONLY, piu' il
 * test GreenMail che asserisce {@code !msg.isSet(SEEN)} dopo un poll completo.
 */
@Service
@Slf4j
public class ImapMailReader {

    private final MailContentExtractor extractor;
    private final SwitchMailProperties props;
    private final Clock clock;

    public ImapMailReader(MailContentExtractor extractor, SwitchMailProperties props, Clock clock) {
        this.extractor = extractor;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ fetch

    /**
     * @param expectedUidValidity UIDVALIDITY nota; se la cartella ne ha un'altra, il fetch non viene
     *                            fatto e la decisione torna al chiamante
     * @param lastUid             high-water mark; ignorato se {@code initial}
     * @param initial             primo poll su questa cartella: si guarda solo la finestra di lookback,
     *                            per non ingerire tutta la casella storica
     */
    public ImapFetchResult fetch(MailAccount account, String password, Long expectedUidValidity,
                                 long lastUid, boolean initial) {
        Store store = null;
        Folder folder = null;
        try {
            Session session = session(account);
            store = connect(session, account, password);
            folder = store.getFolder(account.folder());
            if (folder == null || !folder.exists()) {
                throw new IllegalStateException("cartella inesistente: " + account.folder());
            }
            int mode = account.isReadOnly() ? Folder.READ_ONLY : Folder.READ_WRITE;
            folder.open(mode);
            assertReadOnlyRespected(account, folder);

            UIDFolder uidFolder = (UIDFolder) folder;
            long uidValidity = uidFolder.getUIDValidity();
            if (expectedUidValidity != null && expectedUidValidity != uidValidity) {
                log.error("UIDVALIDITY cambiata su {} cartella {}: {} -> {}",
                        account.name(), account.folder(), expectedUidValidity, uidValidity);
                return new ImapFetchResult(uidValidity, true, List.of());
            }

            Message[] candidates = initial
                    ? initialWindow(folder, account)
                    : incremental(folder, uidFolder, lastUid);

            List<Message> ordered = new ArrayList<>(Arrays.asList(candidates));
            ordered.sort(Comparator.comparingLong(m -> uidOf(uidFolder, m)));
            if (ordered.size() > account.maxMessagesPerPoll()) {
                ordered = ordered.subList(0, account.maxMessagesPerPoll());
            }

            FetchProfile profile = new FetchProfile();
            profile.add(UIDFolder.FetchProfileItem.UID);
            profile.add(FetchProfile.Item.ENVELOPE);
            folder.fetch(ordered.toArray(new Message[0]), profile);

            List<FetchedMail> out = new ArrayList<>(ordered.size());
            for (Message message : ordered) {
                long uid = uidOf(uidFolder, message);
                try {
                    out.add(materialize(session, message, account, uid, uidValidity));
                } catch (Exception e) {
                    // Una mail illeggibile non ferma il batch: lo si logga e si prosegue, altrimenti
                    // un singolo messaggio malformato bloccherebbe per sempre il high-water mark.
                    log.error("Mail uid={} su {} non materializzabile: {}", uid, account.name(), e.getMessage(), e);
                }
            }
            return new ImapFetchResult(uidValidity, false, out);
        } catch (Exception e) {
            throw new IllegalStateException("fetch IMAP fallito su " + account.describe() + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(folder, store);
        }
    }

    /** Primo poll: solo la finestra di lookback, non tutta la casella storica. */
    private Message[] initialWindow(Folder folder, MailAccount account) throws Exception {
        Instant since = clock.instant().minus(account.initialLookbackDays(), ChronoUnit.DAYS);
        return folder.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
    }

    private Message[] incremental(Folder folder, UIDFolder uidFolder, long lastUid) throws Exception {
        Message[] raw = uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
        if (raw == null) {
            return new Message[0];
        }
        // TRAPPOLA: getMessagesByUID(start, LASTUID) ritorna SEMPRE almeno il messaggio con UID piu'
        // alto, anche quando start e' maggiore di esso. Senza questo filtro si rielabora l'ultima
        // mail a ogni poll a vuoto (il claim la scarta, ma il fetch del MIME viene fatto comunque).
        return Arrays.stream(raw)
                .filter(m -> uidOf(uidFolder, m) > lastUid)
                .toArray(Message[]::new);
    }

    /**
     * Materializza scrivendo il MIME grezzo e rileggendolo da un array di byte.
     *
     * <p>Costa una copia in memoria e la ripaga due volte: il parsing avviene su un messaggio locale
     * (nessun fetch lazy a sorpresa mentre la connessione e' gia' chiusa) e lo stesso identico byte
     * stream finisce in mail_raw, quindi l'.eml scaricato da /logs e' esattamente cio' che il parser
     * ha visto.
     */
    private FetchedMail materialize(Session session, Message message, MailAccount account,
                                    long uid, long uidValidity) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        message.writeTo(buffer);
        byte[] raw = buffer.toByteArray();

        MimeMessage local = new MimeMessage(session, new ByteArrayInputStream(raw));
        ParsedMail parsed = extractor.extract(local, account.id(), account.name(), account.folder(),
                uidValidity, uid, raw.length);
        return new FetchedMail(parsed, raw);
    }

    /**
     * Ripesca una singola mail per UID: e' il fallback del bottone Riprova quando il MIME archiviato
     * non c'e' piu' (retention scaduta o archiviazione disattivata) ma la mail e' ancora in casella.
     */
    public Optional<FetchedMail> fetchByUid(MailAccount account, String password, long uidValidity, long uid) {
        Store store = null;
        Folder folder = null;
        try {
            Session session = session(account);
            store = connect(session, account, password);
            folder = store.getFolder(account.folder());
            folder.open(account.isReadOnly() ? Folder.READ_ONLY : Folder.READ_WRITE);
            assertReadOnlyRespected(account, folder);

            UIDFolder uidFolder = (UIDFolder) folder;
            if (uidFolder.getUIDValidity() != uidValidity) {
                log.warn("UIDVALIDITY diversa ({} invece di {}): l'uid {} non identifica piu' la stessa mail",
                        uidFolder.getUIDValidity(), uidValidity, uid);
                return Optional.empty();
            }
            Message message = uidFolder.getMessageByUID(uid);
            if (message == null) {
                return Optional.empty();
            }
            return Optional.of(materialize(session, message, account, uid, uidValidity));
        } catch (Exception e) {
            log.error("Re-fetch uid={} su {} fallito: {}", uid, account.name(), e.getMessage());
            return Optional.empty();
        } finally {
            closeQuietly(folder, store);
        }
    }

    // ------------------------------------------------------------------ test e anteprima

    public TestConnectionResult testConnection(MailAccount account, String password, int previewCount) {
        long t0 = System.currentTimeMillis();
        Store store = null;
        Folder folder = null;
        try {
            Session session = session(account);
            store = connect(session, account, password);
            folder = store.getFolder(account.folder());
            if (folder == null || !folder.exists()) {
                return TestConnectionResult.failure("cartella inesistente: " + account.folder(),
                        System.currentTimeMillis() - t0);
            }
            int mode = account.isReadOnly() ? Folder.READ_ONLY : Folder.READ_WRITE;
            folder.open(mode);
            assertReadOnlyRespected(account, folder);

            String openedMode = folder.getMode() == Folder.READ_ONLY ? "READ_ONLY" : "READ_WRITE";
            long uidValidity = ((UIDFolder) folder).getUIDValidity();
            int count = folder.getMessageCount();
            int unread = folder.getUnreadMessageCount();
            List<MailPreview> previews = envelopes(folder, previewCount);

            return new TestConnectionResult(true, openedMode, account.folder(), uidValidity, count, unread,
                    System.currentTimeMillis() - t0, null, previews);
        } catch (Exception e) {
            return TestConnectionResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        } finally {
            closeQuietly(folder, store);
        }
    }

    public List<MailPreview> preview(MailAccount account, String password, int n) {
        Store store = null;
        Folder folder = null;
        try {
            Session session = session(account);
            store = connect(session, account, password);
            folder = store.getFolder(account.folder());
            folder.open(account.isReadOnly() ? Folder.READ_ONLY : Folder.READ_WRITE);
            assertReadOnlyRespected(account, folder);
            return envelopes(folder, n);
        } catch (Exception e) {
            throw new IllegalStateException("anteprima fallita su " + account.describe() + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(folder, store);
        }
    }

    /**
     * Solo ENVELOPE, UID e FLAGS: nessuna parte del corpo viene richiesta, quindi nemmeno un server
     * che ignorasse BODY.PEEK potrebbe marcare qualcosa come letto. I nomi degli allegati non ci
     * sono per la stessa ragione (servirebbe la struttura del corpo).
     */
    private List<MailPreview> envelopes(Folder folder, int n) throws Exception {
        int total = folder.getMessageCount();
        if (total == 0 || n <= 0) {
            return List.of();
        }
        int from = Math.max(1, total - n + 1);
        Message[] messages = folder.getMessages(from, total);

        FetchProfile profile = new FetchProfile();
        profile.add(UIDFolder.FetchProfileItem.UID);
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        folder.fetch(messages, profile);

        UIDFolder uidFolder = (UIDFolder) folder;
        List<MailPreview> out = new ArrayList<>(messages.length);
        for (Message m : messages) {
            String from1 = null;
            String name = null;
            Address[] senders = m.getFrom();
            if (senders != null && senders.length > 0 && senders[0] instanceof InternetAddress ia) {
                from1 = ia.getAddress();
                name = ia.getPersonal();
            }
            out.add(new MailPreview(uidOf(uidFolder, m), from1, name, m.getSubject(),
                    m.getSentDate() == null ? null : m.getSentDate().toInstant(),
                    m.getReceivedDate() == null ? null : m.getReceivedDate().toInstant(),
                    m.isSet(Flags.Flag.SEEN), List.of()));
        }
        out.sort(Comparator.comparingLong(MailPreview::uid).reversed());
        return out;
    }

    // ------------------------------------------------------------------ post-action (solo OWNED)

    /**
     * Marca/sposta/cancella le mail elaborate. Ammesso solo su account OWNED: su READ_ONLY lo
     * impedisce gia' un CHECK dello schema, qui c'e' comunque la guardia di codice.
     */
    public void applyPostActions(MailAccount account, String password, List<Long> uids) {
        if (account.accessMode() != AccessMode.OWNED || account.postAction() == PostAction.NONE || uids.isEmpty()) {
            return;
        }
        Store store = null;
        Folder folder = null;
        boolean expunge = false;
        try {
            Session session = session(account);
            store = connect(session, account, password);
            folder = store.getFolder(account.folder());
            folder.open(Folder.READ_WRITE);

            UIDFolder uidFolder = (UIDFolder) folder;
            List<Message> messages = new ArrayList<>();
            for (Long uid : uids) {
                Message m = uidFolder.getMessageByUID(uid);
                if (m != null) {
                    messages.add(m);
                }
            }
            if (messages.isEmpty()) {
                return;
            }
            Message[] array = messages.toArray(new Message[0]);

            switch (account.postAction()) {
                case MARK_SEEN -> folder.setFlags(array, new Flags(Flags.Flag.SEEN), true);
                case MOVE -> {
                    Folder target = store.getFolder(account.postActionFolder());
                    if (!target.exists() && !target.create(Folder.HOLDS_MESSAGES)) {
                        throw new IllegalStateException("cartella di destinazione non creabile: "
                                + account.postActionFolder());
                    }
                    folder.copyMessages(array, target);
                    folder.setFlags(array, new Flags(Flags.Flag.DELETED), true);
                    expunge = true;
                }
                case DELETE -> {
                    folder.setFlags(array, new Flags(Flags.Flag.DELETED), true);
                    expunge = true;
                }
                default -> {
                }
            }
            log.info("Post-action {} applicata a {} mail su {}", account.postAction(), array.length, account.name());
        } catch (Exception e) {
            // La mail e' gia' stata elaborata e registrata: una post-action fallita e' un problema di
            // igiene della casella, non un motivo per rielaborare o per perdere l'esito.
            log.error("Post-action {} fallita su {}: {}", account.postAction(), account.name(), e.getMessage());
        } finally {
            // L'unico close(true) del progetto: serve l'expunge per MOVE e DELETE, ed e' raggiungibile
            // solo da qui, cioe' solo per un account OWNED.
            try {
                if (folder != null && folder.isOpen()) {
                    folder.close(expunge);
                }
            } catch (Exception ignored) {
                // niente da fare
            }
            try {
                if (store != null && store.isConnected()) {
                    store.close();
                }
            } catch (Exception ignored) {
                // niente da fare
            }
        }
    }

    // ------------------------------------------------------------------ infrastruttura

    private Session session(MailAccount account) {
        return Session.getInstance(sessionProperties(account));
    }

    /**
     * Le property della sessione, estratte per poterle asserire in un test.
     *
     * <p>GreenMail non riproduce il comportamento che rende necessaria {@code peek}: non marca come
     * letto nemmeno senza. Il test negativo previsto dal piano ("togli peek e verifica che fallisca")
     * contro GreenMail non direbbe nulla, quindi la property e' inchiodata qui, dove un'eventuale
     * rimozione rompe subito ImapSessionPropertiesTest.
     */
    Properties sessionProperties(MailAccount account) {
        String protocol = account.useSsl() ? "imaps" : "imap";
        Properties p = new Properties();
        p.put("mail.store.protocol", protocol);
        // *** La property che rende vera la modalita' non distruttiva. ***
        p.put("mail." + protocol + ".peek", "true");
        p.put("mail." + protocol + ".connectiontimeout", String.valueOf(account.connectTimeoutMs()));
        p.put("mail." + protocol + ".timeout", String.valueOf(account.readTimeoutMs()));
        p.put("mail." + protocol + ".writetimeout", String.valueOf(account.readTimeoutMs()));
        p.put("mail." + protocol + ".fetchsize", "1048576");

        if (account.useSsl()) {
            p.put("mail.imaps.ssl.enable", "true");
            p.put("mail.imaps.ssl.checkserveridentity", String.valueOf(!account.trustAllCerts()));
            if (account.trustAllCerts()) {
                p.put("mail.imaps.ssl.trust", "*");
            }
        }
        if (account.startTls()) {
            p.put("mail." + protocol + ".starttls.enable", "true");
        }
        if (account.trustAllCerts()) {
            // Loggato a ogni poll di proposito: e' una deroga alla verifica del certificato e non
            // deve diventare invisibile col tempo.
            log.warn("Account {}: verifica del certificato disattivata (trust_all_certs)", account.name());
        }
        // mail.debug stampa il comando LOGIN, password inclusa.
        p.put("mail.debug", String.valueOf(props.getImap().isDebug()));

        return p;
    }

    private Store connect(Session session, MailAccount account, String password) throws Exception {
        Store store = session.getStore();
        store.connect(account.host(), account.port(), account.username(), password);
        return store;
    }

    private void assertReadOnlyRespected(MailAccount account, Folder folder) {
        if (account.isReadOnly() && folder.getMode() != Folder.READ_ONLY) {
            throw new IllegalStateException("account READ_ONLY ma la cartella " + account.folder()
                    + " risulta aperta in scrittura: poll abortito");
        }
    }

    private long uidOf(UIDFolder folder, Message message) {
        try {
            return folder.getUID(message);
        } catch (Exception e) {
            throw new IllegalStateException("UID non leggibile: " + e.getMessage(), e);
        }
    }

    /** L'unico close del percorso di lettura: false hardcoded, nessun expunge possibile. */
    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception e) {
            log.debug("Chiusura folder: {}", e.getMessage());
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.debug("Chiusura store: {}", e.getMessage());
        }
    }
}
