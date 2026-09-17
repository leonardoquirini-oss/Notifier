package it.gruppobernardini.switchmail.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tutta la configurazione sotto il prefisso {@code switchmail}. */
@ConfigurationProperties(prefix = "switchmail")
@Getter
@Setter
public class SwitchMailProperties {

    private final Db db = new Db();
    private final Mail mail = new Mail();
    private final Poll poll = new Poll();
    private final Retry retry = new Retry();
    private final Imap imap = new Imap();
    private final Matcher matcher = new Matcher();
    private final Security security = new Security();

    @Getter
    @Setter
    public static class Db {
        /** Path del file SQLite. In Docker: /app/data/switchmail.db (volume). */
        private String path = "./data/switchmail.db";
    }

    @Getter
    @Setter
    public static class Mail {
        /** Archivia il MIME grezzo gzippato: e' cosi' che si scaricano gli .eml da usare come fixture. */
        private boolean storeRaw = true;
        /** Oltre questa soglia il MIME non viene archiviato (warning, non errore). */
        private long rawMaxBytes = 2L * 1024 * 1024;
        /** Retention del MIME grezzo. Il log resta, il blob no. */
        private int rawRetentionDays = 30;
        /** Retention delle righe di log risolte/riuscite. */
        private int logRetentionDays = 365;
        /** Cap per allegato: oltre, si tronca e si registra un warning invece di andare in OOM. */
        private long maxAttachmentBytes = 5L * 1024 * 1024;
    }

    @Getter
    @Setter
    public static class Poll {
        /** Tick dello scheduler: a ogni tick si decide quali account sono scaduti (mail_account.poll_cron). */
        private String tickCron = "0/30 * * * * *";
        /** Quanti account possono avere una connessione IMAP aperta insieme. */
        private int maxConcurrentAccounts = 3;
        /** Se false, nessun poll automatico (utile nei test e in manutenzione). */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Retry {
        private int baseSeconds = 60;
        private int maxSeconds = 1800;
        private int jitterPercent = 10;
        /** Una riga IN_PROGRESS piu' vecchia di questo e' un claim orfano da recuperare. */
        private int staleClaimTimeoutMinutes = 10;
        private String sweepCron = "0 */5 * * * *";
        /**
         * Una mail interrotta dopo la chiamata a BERLink potrebbe aver gia' agito: il retry
         * automatico rischierebbe una scrittura duplicata. Default false = decide un umano.
         */
        private boolean autoRetryInterrupted = false;
        /** Dopo un reset di UIDVALIDITY, le mail piu' vecchie di questo non vengono rielaborate. */
        private int resetLookbackDays = 7;
    }

    @Getter
    @Setter
    public static class Imap {
        /**
         * jakarta.mail con debug=true stampa il comando LOGIN PASSWORD INCLUSA.
         * Da accendere solo su una casella di prova, mai in produzione.
         */
        private boolean debug = false;
    }

    @Getter
    @Setter
    public static class Matcher {
        /** Budget anti-backtracking catastrofico per le regex scritte dall'operatore. */
        private int regexMaxSteps = 100_000;
        /** L'oggetto viene troncato prima del match: input lungo = costo del match. */
        private int subjectMaxChars = 512;
        private int patternCacheSize = 512;
    }

    @Getter
    @Setter
    public static class Security {
        /** Chiave AES-GCM (32 byte base64) per le password IMAP. Nessun default: vedi application.yml. */
        private String credsKey;
    }
}
