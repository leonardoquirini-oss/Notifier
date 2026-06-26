package com.containermgmt.eventcontrolprocessor.repository;

import com.containermgmt.eventcontrolprocessor.engine.Situation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence of detected situations in evt_event_checks. The UNIQUE (rule_code, dedup_key) index
 * is what guarantees "notify once".
 */
@Repository
@Slf4j
public class SituationRepository {

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO evt_event_checks (id_event_check, rule_code, dedup_key, unit_number, status, detected_at, details)
            VALUES (nextval('s_evt_event_checks'), ?, ?, ?, 'OPEN', now(), CAST(? AS jsonb))
            ON CONFLICT (rule_code, dedup_key) DO NOTHING
            """;

    private static final String MARK_NOTIFIED = """
            UPDATE evt_event_checks
            SET status = 'NOTIFIED', notified_at = now()
            WHERE rule_code = ? AND dedup_key = ?
            """;

    private final JdbcTemplate jdbc;

    public SituationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the situation if it has never been seen before.
     * @return true if a new row was inserted (situation is new), false if it already existed.
     */
    public boolean insertIfAbsent(Situation s) {
        int rows = jdbc.update(INSERT_IF_ABSENT,
                s.getRuleCode(),
                s.getDedupKey(),
                s.getUnitNumber(),
                s.getDetailsJson());
        return rows > 0;
    }

    public void markNotified(String ruleCode, String dedupKey) {
        jdbc.update(MARK_NOTIFIED, ruleCode, dedupKey);
    }
}
