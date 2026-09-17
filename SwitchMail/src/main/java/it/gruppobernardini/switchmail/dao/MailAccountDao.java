package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.PostAction;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CRUD di mail_account.
 *
 * <p>Nota di portabilita': i booleani passano sempre da setBoolean/getBoolean (su SQLite sono
 * INTEGER 0/1, su Postgres boolean: il codice non cambia) e i timestamp da TimestampUtil.
 */
@Repository
public class MailAccountDao {

    private static final String COLUMNS = """
            id, name, host, port, use_ssl, start_tls, trust_all_certs, username, password_encrypted,
            folder, access_mode, post_action, post_action_folder, poll_cron, max_messages_per_poll,
            initial_lookback_days, connect_timeout_ms, read_timeout_ms, enabled, last_poll_at,
            last_poll_status, last_poll_error, last_poll_fetched, consecutive_failures, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MailAccountDao(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static final RowMapper<MailAccount> MAPPER = (rs, n) -> new MailAccount(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("host"),
            rs.getInt("port"),
            rs.getBoolean("use_ssl"),
            rs.getBoolean("start_tls"),
            rs.getBoolean("trust_all_certs"),
            rs.getString("username"),
            rs.getBytes("password_encrypted"),
            rs.getString("folder"),
            AccessMode.valueOf(rs.getString("access_mode")),
            PostAction.valueOf(rs.getString("post_action")),
            rs.getString("post_action_folder"),
            rs.getString("poll_cron"),
            rs.getInt("max_messages_per_poll"),
            rs.getInt("initial_lookback_days"),
            rs.getInt("connect_timeout_ms"),
            rs.getInt("read_timeout_ms"),
            rs.getBoolean("enabled"),
            TimestampUtil.parse(rs.getString("last_poll_at")),
            rs.getString("last_poll_status"),
            rs.getString("last_poll_error"),
            (Integer) rs.getObject("last_poll_fetched"),
            rs.getInt("consecutive_failures"),
            TimestampUtil.parse(rs.getString("created_at")),
            TimestampUtil.parse(rs.getString("updated_at")));

    public List<MailAccount> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_account ORDER BY name", MAPPER);
    }

    public List<MailAccount> findEnabled() {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_account WHERE enabled = 1 ORDER BY name", MAPPER);
    }

    public Optional<MailAccount> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_account WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<MailAccount> findByName(String name) {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_account WHERE name = ?", MAPPER, name)
                .stream().findFirst();
    }

    /** RETURNING id: una sola statement, sintassi identica su Postgres. */
    public long insert(MailAccount a) {
        String now = TimestampUtil.now(clock);
        Long id = jdbc.queryForObject("""
                INSERT INTO mail_account (name, host, port, use_ssl, start_tls, trust_all_certs, username,
                    password_encrypted, folder, access_mode, post_action, post_action_folder, poll_cron,
                    max_messages_per_poll, initial_lookback_days, connect_timeout_ms, read_timeout_ms,
                    enabled, consecutive_failures, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
                RETURNING id
                """, Long.class,
                a.name(), a.host(), a.port(), a.useSsl(), a.startTls(), a.trustAllCerts(), a.username(),
                a.passwordEncrypted(), a.folder(), a.accessMode().name(), a.postAction().name(),
                a.postActionFolder(), a.pollCron(), a.maxMessagesPerPoll(), a.initialLookbackDays(),
                a.connectTimeoutMs(), a.readTimeoutMs(), a.enabled(), now, now);
        return id == null ? -1 : id;
    }

    /** Non tocca la password: un round-trip della UI non puo' svuotarla per distrazione. */
    public void update(MailAccount a) {
        jdbc.update("""
                UPDATE mail_account SET name = ?, host = ?, port = ?, use_ssl = ?, start_tls = ?,
                    trust_all_certs = ?, username = ?, folder = ?, access_mode = ?, post_action = ?,
                    post_action_folder = ?, poll_cron = ?, max_messages_per_poll = ?, initial_lookback_days = ?,
                    connect_timeout_ms = ?, read_timeout_ms = ?, enabled = ?, updated_at = ?
                WHERE id = ?
                """,
                a.name(), a.host(), a.port(), a.useSsl(), a.startTls(), a.trustAllCerts(), a.username(),
                a.folder(), a.accessMode().name(), a.postAction().name(), a.postActionFolder(), a.pollCron(),
                a.maxMessagesPerPoll(), a.initialLookbackDays(), a.connectTimeoutMs(), a.readTimeoutMs(),
                a.enabled(), TimestampUtil.now(clock), a.id());
    }

    public void updatePassword(long id, byte[] encrypted) {
        jdbc.update("UPDATE mail_account SET password_encrypted = ?, updated_at = ? WHERE id = ?",
                encrypted, TimestampUtil.now(clock), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM mail_account WHERE id = ?", id);
    }

    public void recordPollSuccess(long id, Instant at, int fetched) {
        jdbc.update("""
                UPDATE mail_account SET last_poll_at = ?, last_poll_status = 'OK', last_poll_error = NULL,
                    last_poll_fetched = ?, consecutive_failures = 0 WHERE id = ?
                """, TimestampUtil.format(at), fetched, id);
    }

    public void recordPollFailure(long id, Instant at, String error) {
        jdbc.update("""
                UPDATE mail_account SET last_poll_at = ?, last_poll_status = 'ERROR', last_poll_error = ?,
                    consecutive_failures = consecutive_failures + 1 WHERE id = ?
                """, TimestampUtil.format(at), abbreviate(error), id);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
    }

    public int countRules(long accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mail_rule WHERE account_id = ? OR account_id IS NULL", Integer.class, accountId);
        return n == null ? 0 : n;
    }
}
