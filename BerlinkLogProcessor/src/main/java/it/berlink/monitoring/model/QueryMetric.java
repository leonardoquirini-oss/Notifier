package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Aggregated metrics for a specific query pattern.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryMetric {

    private String queryHash;
    private String queryPattern;
    private long executionCount;
    private double avgDurationMs;
    private long minDurationMs;
    private long maxDurationMs;
    private long p50DurationMs;
    private long p95DurationMs;
    private long p99DurationMs;
    private Instant firstSeen;
    private Instant lastSeen;
}
