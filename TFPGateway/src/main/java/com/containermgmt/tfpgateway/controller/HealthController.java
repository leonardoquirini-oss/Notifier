package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.dto.GatewayStatusInfo;
import com.containermgmt.tfpgateway.service.GatewayLifecycleManager;
import org.javalite.activejdbc.Base;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoints conformi al contratto BERLink HEALTH_CONTRACT.md.
 *
 * - GET /api/health/live  : liveness pubblico, sempre 200, nessun check dipendenze.
 * - GET /api/health/ready : readiness con check DB + Valkey + Artemis. Richiede X-API-Key.
 *                            503 se almeno una dipendenza e' DOWN.
 * - GET /api/health       : alias deprecato di /ready (back-compat).
 */
@RestController
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    private static final String SERVICE_NAME = "tfp-gateway";
    private static final String SERVICE_VERSION = "1.0.0";

    private final RedisConnectionFactory redisConnectionFactory;
    private final GatewayLifecycleManager lifecycleManager;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String dbDriver;

    @Value("${health.api-key:}")
    private String healthApiKey;

    public HealthController(RedisConnectionFactory redisConnectionFactory,
                            GatewayLifecycleManager lifecycleManager) {
        this.redisConnectionFactory = redisConnectionFactory;
        this.lifecycleManager = lifecycleManager;
    }

    @GetMapping("/api/health/live")
    public ResponseEntity<Map<String, Object>> healthLive() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", SERVICE_NAME);
        body.put("version", SERVICE_VERSION);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, Object>> healthReady(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (healthApiKey == null || healthApiKey.isBlank()) {
            logger.error("health.api-key non configurato: /api/health/ready non utilizzabile");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!healthApiKey.equals(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("database", pingDatabase() ? "UP" : "DOWN");
        checks.put("valkey", pingValkey() ? "UP" : "DOWN");
        checks.put("artemis", pingArtemis() ? "UP" : "DOWN");

        boolean allUp = checks.values().stream().allMatch("UP"::equals);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("service", SERVICE_NAME);
        body.put("version", SERVICE_VERSION);
        body.put("timestamp", Instant.now().toString());
        body.put("checks", checks);

        return ResponseEntity
                .status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    @Deprecated
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> healthAlias(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return healthReady(apiKey);
    }

    private boolean pingDatabase() {
        boolean openedHere = false;
        try {
            if (!Base.hasConnection()) {
                Base.open(dbDriver, dbUrl, dbUsername, dbPassword);
                openedHere = true;
            }
            Connection conn = Base.connection();
            return conn != null && conn.isValid(2);
        } catch (Exception e) {
            logger.warn("Health check DB failed: {}", e.getMessage());
            return false;
        } finally {
            if (openedHere && Base.hasConnection()) {
                try {
                    Base.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private boolean pingValkey() {
        try {
            String pong = redisConnectionFactory.getConnection().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            logger.warn("Health check Valkey failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean pingArtemis() {
        try {
            GatewayStatusInfo status = lifecycleManager.getStatus();
            return "RUNNING".equals(status.getState());
        } catch (Exception e) {
            logger.warn("Health check Artemis failed: {}", e.getMessage());
            return false;
        }
    }
}
