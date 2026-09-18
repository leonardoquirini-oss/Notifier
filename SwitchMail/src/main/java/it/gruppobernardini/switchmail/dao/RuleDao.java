package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** CRUD di mail_rule + l'insieme da valutare per un account. */
@Repository
public class RuleDao {

    private static final String COLUMNS = """
            id, name, description, account_id, enabled, priority, stop_on_match,
            sender_pattern, sender_match, sender_case_sensitive,
            subject_pattern, subject_match, subject_case_sensitive,
            attachment_pattern, attachment_match, attachment_case_sensitive,
            require_attachment, processor_id, params_json, max_attempts, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RuleDao(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static final RowMapper<RuleConfig> MAPPER = (rs, n) -> new RuleConfig(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            JdbcReads.longOrNull(rs, "account_id"),
            rs.getBoolean("enabled"),
            rs.getInt("priority"),
            rs.getBoolean("stop_on_match"),
            check(rs, "sender"),
            check(rs, "subject"),
            check(rs, "attachment"),
            rs.getBoolean("require_attachment"),
            rs.getString("processor_id"),
            rs.getString("params_json"),
            rs.getInt("max_attempts"),
            TimestampUtil.parse(rs.getString("created_at")),
            TimestampUtil.parse(rs.getString("updated_at")));

    private static FieldCheck check(ResultSet rs, String prefix) throws SQLException {
        return new FieldCheck(
                rs.getString(prefix + "_pattern"),
                FieldCheck.MatchMode.valueOf(rs.getString(prefix + "_match")),
                rs.getBoolean(prefix + "_case_sensitive"));
    }

    public List<RuleConfig> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_rule ORDER BY priority ASC, id ASC", MAPPER);
    }

    public Optional<RuleConfig> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_rule WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * Le regole candidate per un account, nell'ordine di valutazione.
     * Il tie-break su id rende l'ordinamento totale: due regole a priorita' 100 non si scambiano
     * di posto tra un poll e l'altro.
     */
    public List<RuleConfig> findForEvaluation(long accountId) {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM mail_rule
                WHERE enabled = 1 AND (account_id = ? OR account_id IS NULL)
                ORDER BY priority ASC, id ASC
                """, MAPPER, accountId);
    }

    /** Tutte le regole abilitate, per la traccia di /ruletest su un account scelto a mano. */
    public List<RuleConfig> findEnabled() {
        return jdbc.query("SELECT " + COLUMNS + " FROM mail_rule WHERE enabled = 1 ORDER BY priority ASC, id ASC",
                MAPPER);
    }

    public long insert(RuleConfig r) {
        String now = TimestampUtil.now(clock);
        Long id = jdbc.queryForObject("""
                INSERT INTO mail_rule (name, description, account_id, enabled, priority, stop_on_match,
                    sender_pattern, sender_match, sender_case_sensitive,
                    subject_pattern, subject_match, subject_case_sensitive,
                    attachment_pattern, attachment_match, attachment_case_sensitive,
                    require_attachment, processor_id, params_json, max_attempts, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id
                """, Long.class,
                r.name(), r.description(), r.accountId(), r.enabled(), r.priority(), r.stopOnMatch(),
                r.sender().pattern(), r.sender().mode().name(), r.sender().caseSensitive(),
                r.subject().pattern(), r.subject().mode().name(), r.subject().caseSensitive(),
                r.attachment().pattern(), r.attachment().mode().name(), r.attachment().caseSensitive(),
                r.requireAttachment(), r.processorId(), r.paramsJson(), r.maxAttempts(), now, now);
        return id == null ? -1 : id;
    }

    public void update(RuleConfig r) {
        jdbc.update("""
                UPDATE mail_rule SET name = ?, description = ?, account_id = ?, enabled = ?, priority = ?,
                    stop_on_match = ?, sender_pattern = ?, sender_match = ?, sender_case_sensitive = ?,
                    subject_pattern = ?, subject_match = ?, subject_case_sensitive = ?,
                    attachment_pattern = ?, attachment_match = ?, attachment_case_sensitive = ?,
                    require_attachment = ?, processor_id = ?, params_json = ?, max_attempts = ?, updated_at = ?
                WHERE id = ?
                """,
                r.name(), r.description(), r.accountId(), r.enabled(), r.priority(), r.stopOnMatch(),
                r.sender().pattern(), r.sender().mode().name(), r.sender().caseSensitive(),
                r.subject().pattern(), r.subject().mode().name(), r.subject().caseSensitive(),
                r.attachment().pattern(), r.attachment().mode().name(), r.attachment().caseSensitive(),
                r.requireAttachment(), r.processorId(), r.paramsJson(), r.maxAttempts(),
                TimestampUtil.now(clock), r.id());
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE mail_rule SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled, TimestampUtil.now(clock), id);
    }

    public void setPriority(long id, int priority) {
        jdbc.update("UPDATE mail_rule SET priority = ?, updated_at = ? WHERE id = ?",
                priority, TimestampUtil.now(clock), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM mail_rule WHERE id = ?", id);
    }

    /** Hit delle ultime 24*giorni ore, per la colonna "usi" della lista regole. */
    public Map<Long, Integer> hitCounts(String sinceIso) {
        Map<Long, Integer> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT rule_id, COUNT(*) AS n FROM mail_processing_log
                WHERE rule_id IS NOT NULL AND created_at >= ?
                GROUP BY rule_id
                """, rs -> {
            out.put(rs.getLong("rule_id"), rs.getInt("n"));
        }, sinceIso);
        return out;
    }
}
