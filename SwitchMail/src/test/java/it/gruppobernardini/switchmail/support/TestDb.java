package it.gruppobernardini.switchmail.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * Un database SQLite vero su file temporaneo, con lo schema di produzione.
 *
 * <p>Niente H2 e niente mock dei DAO: lo schema porta i CHECK, l'indice unico di dedup e le foreign
 * key, cioe' esattamente le cose su cui si vuole avere fiducia. L'URL replica quello di produzione,
 * PRAGMA comprese.
 */
public class TestDb implements AutoCloseable {

    private final Path file;
    private final SingleConnectionDataSource dataSource;
    private final JdbcTemplate jdbc;

    public TestDb() {
        try {
            this.file = Files.createTempFile("switchmail-test-", ".db");
            Files.deleteIfExists(file);
            String url = "jdbc:sqlite:" + file.toAbsolutePath()
                    + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000";
            this.dataSource = new SingleConnectionDataSource(url, true);
            this.dataSource.setDriverClassName("org.sqlite.JDBC");
            this.jdbc = new JdbcTemplate(dataSource);
            try (Connection c = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema.sql"));
            }
            Integer fk = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
            if (fk == null || fk != 1) {
                throw new IllegalStateException("foreign_keys non attive nel DB di test");
            }
        } catch (Exception e) {
            throw new IllegalStateException("creazione del DB di test fallita", e);
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    public String status(long logId) {
        return jdbc.queryForObject("SELECT status FROM mail_processing_log WHERE id = ?", String.class, logId);
    }

    /** Casella minima gia' valida: READ_ONLY, senza post-action. */
    public long insertAccount(String name) {
        return jdbc.queryForObject("""
                INSERT INTO mail_account (name, host, port, use_ssl, start_tls, trust_all_certs, username,
                    folder, access_mode, post_action, poll_cron, max_messages_per_poll, initial_lookback_days,
                    connect_timeout_ms, read_timeout_ms, enabled, consecutive_failures, created_at, updated_at)
                VALUES (?, 'imap.test', 993, 1, 0, 0, 'service', 'INBOX', 'READ_ONLY', 'NONE', '0 */2 * * * *',
                    50, 1, 10000, 30000, 1, 0, '2026-09-17T08:00:00.000Z', '2026-09-17T08:00:00.000Z')
                RETURNING id
                """, Long.class, name);
    }

    public long insertRule(String name, Long accountId, String processorId, String subjectPattern,
                           int priority, boolean stopOnMatch, String paramsJson) {
        return jdbc.queryForObject("""
                INSERT INTO mail_rule (name, account_id, enabled, priority, stop_on_match,
                    subject_pattern, subject_match, subject_case_sensitive,
                    sender_match, sender_case_sensitive, attachment_match, attachment_case_sensitive,
                    require_attachment, processor_id, params_json, max_attempts, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?, ?, 'CONTAINS', 0, 'CONTAINS', 0, 'CONTAINS', 0, 0, ?, ?, 3,
                    '2026-09-17T08:00:00.000Z', '2026-09-17T08:00:00.000Z')
                RETURNING id
                """, Long.class, name, accountId, priority, stopOnMatch, subjectPattern, processorId, paramsJson);
    }

    @Override
    public void close() {
        try {
            dataSource.destroy();
        } catch (Exception ignored) {
            // niente da fare
        }
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
        } catch (Exception ignored) {
            // niente da fare
        }
    }
}
