package com.containermgmt.eventcontrolprocessor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;

/**
 * Applies the evt_event_checks DDL on startup. The script's CREATE TABLE has no IF NOT EXISTS, so on
 * subsequent boots the table already exists: continueOnError lets those "already exists" errors pass
 * (the sequence/table/indexes are created only on the first run).
 */
@Component
@Slf4j
public class SchemaInitializer {

    private static final String SCRIPT = "db/01_evt_event_checks.sql";

    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource(SCRIPT));
        populator.setContinueOnError(true);
        try {
            populator.execute(dataSource);
            log.info("evt_event_checks schema ensured ({})", SCRIPT);
        } catch (Exception e) {
            log.error("Failed to apply schema {}: {}", SCRIPT, e.getMessage(), e);
            throw new RuntimeException("Schema initialization failed", e);
        }
    }
}
