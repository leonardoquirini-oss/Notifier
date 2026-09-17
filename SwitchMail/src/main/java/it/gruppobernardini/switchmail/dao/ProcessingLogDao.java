package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.dto.LogFilter;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.util.JsonUtil;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Il registro di dedup, di audit e di dead-letter. */
@Repository
public class ProcessingLogDao {

    private static final String SELECT = """
            SELECT l.*, a.name AS account_name,
                   (SELECT 1 FROM mail_raw r WHERE r.log_id = l.id) AS raw_present
            FROM mail_processing_log l
            LEFT JOIN mail_account a ON a.id = l.account_id
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessingLogDao(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static final RowMapper<ProcessingLogEntry> MAPPER = (rs, n) -> new ProcessingLogEntry(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("account_name"),
            rs.getString("folder"),
            rs.getLong("uid_validity"),
            rs.getLong("uid"),
            rs.getString("internet_message_id"),
            rs.getString("mail_from"),
            rs.getString("mail_from_name"),
            rs.getString("mail_subject"),
            TimestampUtil.parse(rs.getString("mail_sent_at")),
            TimestampUtil.parse(rs.getString("mail_received_at")),
            JsonUtil.readStringList(rs.getString("attachment_names")),
            (Integer) rs.getObject("mail_size_bytes"),
            (Long) rs.getObject("rule_id"),
            rs.getString("rule_name"),
            readIds(rs.getString("matched_rule_ids")),
            rs.getString("processor_id"),
            ProcessingStatus.of(rs.getString("status")),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"),
            rs.getString("message"),
            rs.getString("extracted_json"),
            rs.getString("action_ref"),
            JsonUtil.readStringList(rs.getString("warnings")),
            rs.getString("error_type"),
            rs.getString("error_message"),
            rs.getString("error_stack"),
            (Long) rs.getObject("duration_ms"),
            TimestampUtil.parse(rs.getString("claimed_at")),
            TimestampUtil.parse(rs.getString("processed_at")),
            TimestampUtil.parse(rs.getString("next_retry_at")),
            TimestampUtil.parse(rs.getString("resolved_at")),
            rs.getString("resolved_note"),
            TimestampUtil.parse(rs.getString("created_at")),
            rs.getObject("raw_present") != null);

    private static List<Long> readIds(String json) {
        return JsonUtil.readStringList(json).stream().map(s -> Long.valueOf(String.valueOf(s))).toList();
    }

    /**
     * Il claim atomico. <b>E' la decisione di dedup</b>, in una sola statement.
     *
     * <p>Una riga tornata = la mail e' nostra, e RETURNING porta l'id senza un secondo round-trip.
     * Nessuna riga = qualcuno l'ha gia' presa in carico (altro processo, o questo stesso poll dopo
     * un crash a meta' batch). Niente race read-then-write, niente lock applicativo.
     *
     * <p>ON CONFLICT DO NOTHING + RETURNING: sintassi identica su SQLite e su Postgres.
     */
    public OptionalLong claim(ParsedMail mail, int maxAttempts) {
        String now = TimestampUtil.now(clock);
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO mail_processing_log (
                    account_id, folder, uid_validity, uid, internet_message_id,
                    mail_from, mail_from_name, mail_subject, mail_sent_at, mail_received_at,
                    attachment_names, mail_size_bytes, status, attempt, max_attempts,
                    warnings, claimed_at, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'IN_PROGRESS',0,?,?,?,?)
                ON CONFLICT (account_id, folder, uid_validity, uid) DO NOTHING
                RETURNING id
                """, Long.class,
                mail.accountId(), mail.folder(), mail.uidValidity(), mail.uid(), mail.internetMessageId(),
                mail.fromAddress(), mail.fromDisplayName(), mail.subject(),
                TimestampUtil.format(mail.sentAt()), TimestampUtil.format(mail.receivedAt()),
                JsonUtil.write(mail.attachmentNames()), mail.rawSizeBytes(), maxAttempts,
                JsonUtil.write(mail.extractionWarnings()), now, now);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    public Optional<ProcessingLogEntry> find(long id) {
        return jdbc.query(SELECT + " WHERE l.id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * Chiave secondaria, usata solo dopo un reset di UIDVALIDITY: Exchange non garantisce la
     * presenza del Message-ID sui messaggi generati internamente, ed e' falsificabile dal client.
     */
    public boolean existsByInternetMessageId(long accountId, String internetMessageId) {
        if (internetMessageId == null || internetMessageId.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_processing_log
                WHERE account_id = ? AND internet_message_id = ?
                """, Integer.class, accountId, internetMessageId);
        return n != null && n > 0;
    }

    /** Il binding regola/processore, scritto appena il matcher ha deciso. */
    public void setBinding(long id, Long ruleId, String ruleName, List<Long> matchedRuleIds, String processorId,
                           int maxAttempts) {
        jdbc.update("""
                UPDATE mail_processing_log
                SET rule_id = ?, rule_name = ?, matched_rule_ids = ?, processor_id = ?, max_attempts = ?
                WHERE id = ?
                """, ruleId, ruleName, JsonUtil.write(matchedRuleIds), processorId, maxAttempts, id);
    }

    public void recordTerminalState(long id, ProcessingStatus status, int attempt, String message,
                                    String extractedJson, String actionRef, List<String> warnings,
                                    String errorType, String errorMessage, String errorStack, Long durationMs) {
        jdbc.update("""
                UPDATE mail_processing_log
                SET status = ?, attempt = ?, message = ?, extracted_json = ?, action_ref = ?,
                    warnings = ?, error_type = ?, error_message = ?, error_stack = ?, duration_ms = ?,
                    processed_at = ?, next_retry_at = NULL
                WHERE id = ?
                """, status.name(), attempt, message, extractedJson, actionRef,
                JsonUtil.write(warnings == null ? List.of() : warnings),
                errorType, errorMessage, errorStack, durationMs, TimestampUtil.now(clock), id);
    }

    public void scheduleRetry(long id, int attempt, Instant nextRetryAt, String message,
                              String errorType, String errorMessage, String errorStack, Long durationMs) {
        jdbc.update("""
                UPDATE mail_processing_log
                SET status = 'RETRY_SCHEDULED', attempt = ?, message = ?, error_type = ?, error_message = ?,
                    error_stack = ?, duration_ms = ?, processed_at = ?, next_retry_at = ?
                WHERE id = ?
                """, attempt, message, errorType, errorMessage, errorStack, durationMs,
                TimestampUtil.now(clock), TimestampUtil.format(nextRetryAt), id);
    }

    /** Rimette una riga in lavorazione: retry automatico o manuale. */
    public void reclaim(long id) {
        jdbc.update("""
                UPDATE mail_processing_log
                SET status = 'IN_PROGRESS', claimed_at = ?, next_retry_at = NULL
                WHERE id = ?
                """, TimestampUtil.now(clock), id);
    }

    public void markResolved(long id, String note) {
        jdbc.update("""
                UPDATE mail_processing_log
                SET status = 'RESOLVED', resolved_at = ?, resolved_note = ?
                WHERE id = ?
                """, TimestampUtil.now(clock), note, id);
    }

    public List<ProcessingLogEntry> findDueRetries(Instant now, int limit) {
        return jdbc.query(SELECT + """
                WHERE l.status = 'RETRY_SCHEDULED' AND l.next_retry_at <= ?
                ORDER BY l.next_retry_at ASC LIMIT ?
                """, MAPPER, TimestampUtil.format(now), limit);
    }

    public List<ProcessingLogEntry> findStaleClaims(Instant olderThan, int limit) {
        return jdbc.query(SELECT + """
                WHERE l.status = 'IN_PROGRESS' AND l.claimed_at < ?
                ORDER BY l.claimed_at ASC LIMIT ?
                """, MAPPER, TimestampUtil.format(olderThan), limit);
    }

    /** Paginazione keyset su (created_at DESC, id DESC): niente OFFSET che degrada con la tabella. */
    public List<ProcessingLogEntry> page(LogFilter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (f.accountId() != null) {
            sql.append(" AND l.account_id = ?");
            args.add(f.accountId());
        }
        if (!f.statuses().isEmpty()) {
            sql.append(" AND l.status IN (")
               .append("?,".repeat(f.statuses().size() - 1)).append("?)");
            f.statuses().forEach(s -> args.add(s.name()));
        }
        if (f.ruleId() != null) {
            sql.append(" AND l.rule_id = ?");
            args.add(f.ruleId());
        }
        if (f.text() != null && !f.text().isBlank()) {
            sql.append(" AND (l.mail_subject LIKE ? OR l.mail_from LIKE ? OR l.mail_from_name LIKE ?)");
            String like = "%" + f.text().trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (f.fromIso() != null) {
            sql.append(" AND l.created_at >= ?");
            args.add(f.fromIso());
        }
        if (f.toIso() != null) {
            sql.append(" AND l.created_at <= ?");
            args.add(f.toIso());
        }
        if (f.cursorCreatedAt() != null && f.cursorId() != null) {
            sql.append(" AND (l.created_at < ? OR (l.created_at = ? AND l.id < ?))");
            args.add(f.cursorCreatedAt());
            args.add(f.cursorCreatedAt());
            args.add(f.cursorId());
        }
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ?");
        args.add(f.limit());

        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** Contatori in testata a /logs, aggiornati ogni 30 s. */
    public Map<String, Integer> countsByStatus() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS n FROM mail_processing_log GROUP BY status", rs -> {
            out.put(rs.getString("status"), rs.getInt("n"));
        });
        return out;
    }

    public int deleteResolvedOlderThan(Instant threshold) {
        return jdbc.update("""
                DELETE FROM mail_processing_log
                WHERE created_at < ? AND status IN ('SUCCESS','SKIPPED','RESOLVED')
                """, TimestampUtil.format(threshold));
    }
}
