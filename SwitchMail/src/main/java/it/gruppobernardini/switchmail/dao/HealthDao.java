package it.gruppobernardini.switchmail.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Il ping del database per /api/health/ready.
 *
 * <p>Esiste per non far comparire {@code JdbcTemplate} in un controller: la regola "SQL solo in
 * dao/" vale anche per una SELECT 1, altrimenti diventa il primo di una serie di strappi.
 */
@Repository
public class HealthDao {

    private final JdbcTemplate jdbc;

    public HealthDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tocca davvero il file: un volume smontato o un DB corrotto devono risultare DOWN. */
    public void ping() {
        jdbc.queryForObject("SELECT COUNT(*) FROM schema_meta", Integer.class);
    }
}
