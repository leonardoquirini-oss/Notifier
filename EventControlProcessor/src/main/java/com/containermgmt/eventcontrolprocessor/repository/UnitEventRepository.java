package com.containermgmt.eventcontrolprocessor.repository;

import com.containermgmt.eventcontrolprocessor.model.UnitEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only access to evt_unit_events (populated by TFPEventIngester) for control rules.
 */
@Repository
@Slf4j
public class UnitEventRepository {

    /**
     * Events of a given type whose window is "ripe" for evaluation: old enough that the grace period
     * has elapsed, but not older than the lookback bound (keeps the scan small).
     */
    private static final String EVENTS_IN_WINDOW = """
            SELECT id_unit_event, type, unit_number, event_time, latitude, longitude
            FROM evt_unit_events
            WHERE type = ?
              AND unit_number IS NOT NULL
              AND event_time >= now() - make_interval(hours => ?)
              AND event_time <= now() - make_interval(hours => ?)
            ORDER BY event_time
            """;

    /** Candidate "closing" events for the same unit, same calendar day, at/after the opening event. */
    private static final String CLOSING_CANDIDATES = """
            SELECT id_unit_event, type, unit_number, event_time, latitude, longitude
            FROM evt_unit_events
            WHERE type = ?
              AND unit_number = ?
              AND event_time::date = ?::date
              AND event_time >= ?
            """;

    private static final RowMapper<UnitEvent> MAPPER = UnitEventRepository::mapRow;

    private final JdbcTemplate jdbc;

    public UnitEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UnitEvent> findRipeEvents(String type, int lookbackHours, int graceHours) {
        return jdbc.query(EVENTS_IN_WINDOW, MAPPER, type, lookbackHours, graceHours);
    }

    public List<UnitEvent> findClosingCandidates(String closingType, String unitNumber, LocalDateTime openingTime) {
        Timestamp ts = Timestamp.valueOf(openingTime);
        return jdbc.query(CLOSING_CANDIDATES, MAPPER, closingType, unitNumber, ts, ts);
    }

    private static UnitEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp eventTime = rs.getTimestamp("event_time");
        BigDecimal lat = rs.getBigDecimal("latitude");
        BigDecimal lon = rs.getBigDecimal("longitude");
        return new UnitEvent(
                rs.getLong("id_unit_event"),
                rs.getString("type"),
                rs.getString("unit_number"),
                eventTime != null ? eventTime.toLocalDateTime() : null,
                lat != null ? lat.doubleValue() : null,
                lon != null ? lon.doubleValue() : null
        );
    }
}
