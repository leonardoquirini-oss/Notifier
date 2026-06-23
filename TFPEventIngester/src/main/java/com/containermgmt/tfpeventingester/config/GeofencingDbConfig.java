package com.containermgmt.tfpeventingester.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JdbcTemplate SQLite dedicato alla feature Geofencing.
 *
 * NOTA: il DataSource SQLite NON viene esposto come bean Spring. Esporre un secondo
 * bean di tipo {@link DataSource} farebbe il backoff di DataSourceAutoConfiguration
 * (@ConditionalOnMissingBean) lasciando ActiveJDBCConfig senza il DataSource Postgres.
 * Quindi il pool viene costruito internamente e si espone solo il JdbcTemplate.
 */
@Configuration
@Slf4j
public class GeofencingDbConfig {

    private final String dbPath;

    public GeofencingDbConfig(@Value("${geofencing.db-path:./data/geofencing.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    @Bean(name = "geofencingJdbcTemplate")
    public JdbcTemplate geofencingJdbcTemplate() {
        DataSource ds = buildDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        initSchema(jdbc);
        return jdbc;
    }

    private DataSource buildDataSource() {
        try {
            Path parent = new File(dbPath).getAbsoluteFile().toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile creare la directory per il DB SQLite: " + dbPath, e);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setPoolName("Geofencing-SQLite-Pool");
        // SQLite: una sola connessione scrittore + FK on per ogni connessione
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA foreign_keys = ON");
        log.info("Geofencing SQLite DataSource su {}", new File(dbPath).getAbsolutePath());
        return new HikariDataSource(cfg);
    }

    private void initSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS geo_map (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS geo_point (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  map_id INTEGER NOT NULL REFERENCES geo_map(id) ON DELETE CASCADE,
                  label TEXT,
                  latitude REAL NOT NULL,
                  longitude REAL NOT NULL,
                  radius_m REAL NOT NULL,
                  color TEXT NOT NULL DEFAULT '#0d6efd',
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_geo_point_map ON geo_point(map_id)");
        // Migrazione DB esistenti: aggiunge la colonna color se mancante
        if (!columnExists(jdbc, "geo_point", "color")) {
            jdbc.execute("ALTER TABLE geo_point ADD COLUMN color TEXT NOT NULL DEFAULT '#0d6efd'");
            log.info("Migrazione: aggiunta colonna geo_point.color");
        }
        log.info("Schema Geofencing SQLite inizializzato");
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(r -> column.equalsIgnoreCase(String.valueOf(r.get("name"))));
    }
}
