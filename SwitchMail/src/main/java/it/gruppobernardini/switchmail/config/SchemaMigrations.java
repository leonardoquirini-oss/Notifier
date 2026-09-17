package it.gruppobernardini.switchmail.config;

import it.gruppobernardini.switchmail.util.TimestampUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

/**
 * Crea lo schema e applica le migration additive, in ordine e con la versione registrata in
 * schema_meta. E' il pattern columnExists() + ALTER TABLE di GeofencingDbConfig, ma versionato e
 * loggato invece che un mucchio non ordinato di if.
 *
 * <p>Lo schema base sta in db/schema.sql (7 tabelle, tutte CREATE TABLE IF NOT EXISTS) e viene
 * eseguito a ogni avvio: e' un file diffabile, non 200 righe di stringhe dentro un metodo @Bean.
 */
@Component
@Slf4j
public class SchemaMigrations {

    /** Alzare di 1 e aggiungere lo step in MIGRATIONS quando serve cambiare uno schema gia' in campo. */
    static final int CURRENT_VERSION = 1;

    private static final String VERSION_KEY = "schema_version";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SwitchMailProperties props;

    /** Migration additive, applicate in ordine crescente di versione. */
    private static final List<Migration> MIGRATIONS = List.of(
            // new Migration(2, "mail_rule: colonna xyz", jdbc -> jdbc.execute("ALTER TABLE ..."))
    );

    public SchemaMigrations(DataSource dataSource, Clock clock, SwitchMailProperties props) {
        this.dataSource = dataSource;
        // JdbcTemplate costruito a mano: cosi' la creazione dello schema non dipende
        // dall'ordine di inizializzazione dei bean auto-configurati.
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
        this.props = props;
    }

    @PostConstruct
    public void migrate() {
        verifyOpenedFile();
        runBaseSchema();
        logEffectivePragmas();

        int from = readVersion();
        int applied = 0;
        for (Migration m : MIGRATIONS) {
            if (m.version() > from) {
                log.info("Migration {} - {}", m.version(), m.description());
                m.step().accept(jdbc);
                writeVersion(m.version());
                applied++;
            }
        }
        if (from == 0) {
            writeVersion(CURRENT_VERSION);
            log.info("Schema creato, versione {}", CURRENT_VERSION);
        } else if (applied > 0) {
            log.info("Schema aggiornato: {} -> {} ({} migration)", from, readVersion(), applied);
        } else {
            log.info("Schema gia' alla versione {}", from);
        }
    }

    /**
     * Quale file e' stato aperto davvero.
     *
     * <p>Il driver xerial applica solo le PRAGMA che riconosce e lascia le altre attaccate al nome
     * del file: una query string sbagliata nell'URL JDBC crea in silenzio un DB chiamato
     * "switchmail.db?qualcosa=1", e l'applicazione parte felice su un database vuoto che nessuno
     * andra' mai a cercare. Un confronto a ogni avvio costa una query e chiude il caso.
     */
    private void verifyOpenedFile() {
        String opened = jdbc.queryForObject(
                "SELECT file FROM pragma_database_list WHERE name = 'main'", String.class);
        String expected = new java.io.File(props.getDb().getPath()).getAbsolutePath();
        if (opened == null || opened.isBlank()) {
            throw new IllegalStateException("SQLite ha aperto un database in memoria invece di " + expected);
        }
        java.nio.file.Path openedPath = java.nio.file.Path.of(opened).normalize();
        java.nio.file.Path expectedPath = java.nio.file.Path.of(expected).normalize();
        if (!openedPath.equals(expectedPath)) {
            throw new IllegalStateException(
                    "Il DB aperto non e' quello configurato: aperto=" + openedPath + ", atteso=" + expectedPath
                    + " (tipico sintomo di una PRAGMA non riconosciuta rimasta nella query string dell'URL JDBC)");
        }
        log.info("DB aperto: {}", openedPath);
    }

    private void runBaseSchema() {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("Creazione dello schema fallita", e);
        }
    }

    /**
     * Le PRAGMA arrivano dalla query string dell'URL JDBC: se il driver le ignorasse, il WAL non
     * sarebbe attivo e le foreign key non verrebbero applicate (e ON DELETE CASCADE sarebbe una
     * bugia). Verificarle e' piu' economico che scoprirlo da un dato inconsistente.
     */
    private void logEffectivePragmas() {
        String journal = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        Integer fk = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        log.info("PRAGMA effettive: journal_mode={}, foreign_keys={}", journal, fk);
        if (fk == null || fk != 1) {
            throw new IllegalStateException("PRAGMA foreign_keys non attiva: ON DELETE CASCADE non funzionerebbe");
        }
        if (!"wal".equalsIgnoreCase(journal)) {
            log.warn("journal_mode={} invece di WAL: letture e scritture si bloccheranno a vicenda", journal);
        }
    }

    private int readVersion() {
        List<String> rows = jdbc.queryForList(
                "SELECT value FROM schema_meta WHERE key = ?", String.class, VERSION_KEY);
        return rows.isEmpty() ? 0 : Integer.parseInt(rows.get(0));
    }

    private void writeVersion(int version) {
        String now = TimestampUtil.now(clock);
        jdbc.update("""
                INSERT INTO schema_meta (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """, VERSION_KEY, String.valueOf(version), now);
    }

    record Migration(int version, String description, Consumer<JdbcTemplate> step) {
    }
}
