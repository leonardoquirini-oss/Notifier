package com.containermgmt.tfpeventingester.config;

import org.javalite.activejdbc.Base;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

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
            Class.forName(driverClassName);
            Base.open(driverClassName, dbUrl, dbUsername, dbPassword);
            log.debug("ActiveJDBC database connection established successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ActiveJDBC database connection", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (Base.hasConnection()) {
            Base.close();
            log.debug("ActiveJDBC database connection closed");
        }
    }

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
