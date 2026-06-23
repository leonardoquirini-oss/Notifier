package com.containermgmt.gateeventprocessor.repository;

import com.containermgmt.gateeventprocessor.model.Gate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads gate definitions from BERLink wrk_gates, resolving the notification group_code
 * via the ntf_notification_groups FK.
 */
@Repository
@Slf4j
public class GateRepository {

    private static final String GATES_QUERY = """
            SELECT g.id_gate,
                   g.label,
                   g.latitude,
                   g.longitude,
                   g.radius,
                   ng.group_code
            FROM wrk_gates g
            LEFT JOIN ntf_notification_groups ng
                   ON ng.id_notification_group = g.id_notification_group
            """;

    private final JdbcTemplate jdbc;

    public GateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Gate> findAll() {
        return jdbc.query(GATES_QUERY, (rs, rowNum) -> new Gate(
                rs.getInt("id_gate"),
                rs.getString("label"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getDouble("radius"),
                rs.getString("group_code")));
    }
}
