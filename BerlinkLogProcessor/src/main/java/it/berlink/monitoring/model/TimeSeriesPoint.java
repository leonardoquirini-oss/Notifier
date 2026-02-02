package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single point in a time series chart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesPoint {

    private Instant timestamp;
    private double avgDurationMs;
    private long p95DurationMs;
    private long executionCount;
}
