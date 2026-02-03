package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO for query metrics with trend information.
 * Extends QueryMetric fields with trend data for windowed P95 comparison.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryMetricWithTrend {

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
    private String topMethod;

    // Trend fields
    private String trend;        // "improving" / "degrading" / "stable" / "new"
    private double trendPercent; // e.g. -15.3 = improved 15.3%
    private Long previousP95Ms; // P95 from previous window (null if absent)
    private double impactScore;

    public static QueryMetricWithTrend fromMetric(QueryMetric m) {
        return QueryMetricWithTrend.builder()
            .queryHash(m.getQueryHash())
            .queryPattern(m.getQueryPattern())
            .executionCount(m.getExecutionCount())
            .avgDurationMs(m.getAvgDurationMs())
            .minDurationMs(m.getMinDurationMs())
            .maxDurationMs(m.getMaxDurationMs())
            .p50DurationMs(m.getP50DurationMs())
            .p95DurationMs(m.getP95DurationMs())
            .p99DurationMs(m.getP99DurationMs())
            .firstSeen(m.getFirstSeen())
            .lastSeen(m.getLastSeen())
            .topMethod(m.getTopMethod())
            .impactScore(m.getImpactScore())
            .build();
    }
}
