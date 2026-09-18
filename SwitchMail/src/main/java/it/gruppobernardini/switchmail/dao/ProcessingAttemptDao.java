package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.model.AttemptStatus;
import it.gruppobernardini.switchmail.model.TriggeredBy;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Audit append-only dei tentativi.
 *
 * <p>Tiene il log a una-riga-per-mail (cosi' l'indice unico puo' <i>essere</i> il meccanismo di
 * dedup) senza perdere la traccia: "tentativo 1 fallito BERLINK_5XX, tentativo 2 manuale riuscito".
 * E' anche l'unico posto dove atterrano i risultati per-regola quando stop_on_match = 0.
 */
@Repository
public class ProcessingAttemptDao {

    private final JdbcTemplate jdbc;

    public ProcessingAttemptDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Map<String, Object>> MAPPER = (rs, n) -> {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("attempt", rs.getInt("attempt"));
        m.put("ruleId", JdbcReads.longOrNull(rs, "rule_id"));
        m.put("processorId", rs.getString("processor_id"));
        m.put("status", rs.getString("status"));
        m.put("message", rs.getString("message"));
        m.put("errorType", rs.getString("error_type"));
        m.put("errorMessage", rs.getString("error_message"));
        m.put("durationMs", JdbcReads.longOrNull(rs, "duration_ms"));
        m.put("triggeredBy", rs.getString("triggered_by"));
        m.put("startedAt", rs.getString("started_at"));
        m.put("finishedAt", rs.getString("finished_at"));
        return m;
    };

    public void insert(long logId, int attempt, Long ruleId, String processorId, AttemptStatus status,
                       String message, String errorType, String errorMessage, Long durationMs,
                       TriggeredBy triggeredBy, Instant startedAt, Instant finishedAt) {
        jdbc.update("""
                INSERT INTO mail_processing_attempt (log_id, attempt, rule_id, processor_id, status, message,
                    error_type, error_message, duration_ms, triggered_by, started_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, logId, attempt, ruleId, processorId, status.name(), message, errorType, errorMessage,
                durationMs, triggeredBy.name(), TimestampUtil.format(startedAt), TimestampUtil.format(finishedAt));
    }

    /** La timeline mostrata espandendo una riga di /logs. */
    public List<Map<String, Object>> findByLog(long logId) {
        return jdbc.query("""
                SELECT attempt, rule_id, processor_id, status, message, error_type, error_message,
                       duration_ms, triggered_by, started_at, finished_at
                FROM mail_processing_attempt WHERE log_id = ? ORDER BY attempt ASC, id ASC
                """, MAPPER, logId);
    }
}
