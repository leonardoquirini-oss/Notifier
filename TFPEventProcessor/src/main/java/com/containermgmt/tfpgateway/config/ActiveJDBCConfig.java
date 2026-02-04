package com.containermgmt.tfpgateway.config;

import org.javalite.activejdbc.Base;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * ActiveJDBC Database Configuration
 *
 * Manages PostgreSQL database connection using ActiveJDBC.
 * Opens connection on startup and closes on shutdown.
 */
@Configuration
@Slf4j
public class ActiveJDBCConfig {

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    @PostConstruct
    public void init() {
        try {
            // Load the PostgreSQL driver
            Class.forName(driverClassName);

            // Open ActiveJDBC connection
            Base.open(driverClassName, dbUrl, dbUsername, dbPassword);

            log.info("ActiveJDBC database connection established successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ActiveJDBC database connection", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (Base.hasConnection()) {
            Base.close();
            log.info("ActiveJDBC database connection closed");
        }
    }

    /**
     * Opens a new database connection for the current thread.
     * Use this in multi-threaded scenarios (e.g., JMS listeners).
     */
    public void openConnection() {
        if (!Base.hasConnection()) {
            Base.open(driverClassName, dbUrl, dbUsername, dbPassword);
        }
    }

    /**
     * Closes the database connection for the current thread.
     */
    public void closeConnection() {
        if (Base.hasConnection()) {
            Base.close();
        }
    }

    // Getter methods for components that need direct access
    public String getDbUrl() {
        return dbUrl;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

}
