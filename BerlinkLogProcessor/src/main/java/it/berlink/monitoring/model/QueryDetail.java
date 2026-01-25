package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Detailed information about a query including its metrics and recent executions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryDetail {

    private QueryMetric metrics;
    private List<ExecutionPoint> recentExecutions;
}
