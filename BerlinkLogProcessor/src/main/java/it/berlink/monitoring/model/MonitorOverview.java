package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Overview statistics for the query monitor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorOverview {

    private long totalQueriesTracked;
    private long totalExecutions;
    private double avgDurationMs;
    private long slowestQueryP95Ms;
    private String slowestQueryHash;
    private Instant monitoringStartTime;
    private Instant lastUpdateTime;
}
