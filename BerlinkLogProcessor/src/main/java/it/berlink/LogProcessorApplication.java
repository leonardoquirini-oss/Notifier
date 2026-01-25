package it.berlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BerlinkLogProcessor - SQL Query Monitoring from Log Files
 *
 * This application monitors a log file containing SQL query execution data,
 * calculates performance metrics (P50, P95, P99, count, avg, min, max),
 * and exposes them via REST API.
 *
 * Features:
 * - Real-time log file monitoring with polling
 * - Log rotation support (detects file truncation/replacement)
 * - Fault tolerance (persists read position to Valkey)
 * - Configurable TTL for metrics (default 15 days)
 * - REST API for querying metrics
 */
@SpringBootApplication
public class LogProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogProcessorApplication.class, args);
    }
}
