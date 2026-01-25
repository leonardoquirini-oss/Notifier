package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single execution of a SQL query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPoint {

    private Instant timestamp;
    private long durationMs;
    private int rowCount;
    private String method;
}
