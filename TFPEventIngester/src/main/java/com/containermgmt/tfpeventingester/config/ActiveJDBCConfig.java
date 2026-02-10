package com.containermgmt.tfpeventingester.config;

import org.javalite.activejdbc.Base;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class ActiveJDBCConfig {

    private final DataSource dataSource;

    public ActiveJDBCConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    @PostConstruct
    public void init() {
        try {
            Base.open(dataSource);
            log.debug("ActiveJDBC connection opened via pooled DataSource");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ActiveJDBC database connection", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (Base.hasConnection()) {
            Base.close();
            log.debug("ActiveJDBC connection returned to pool");
        }
    }
}
