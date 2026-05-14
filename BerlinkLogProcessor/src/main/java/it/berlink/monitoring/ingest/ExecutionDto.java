package it.berlink.monitoring.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One query execution as shipped to FlowCenter central.
 * Field names map to the central ingestion contract (snake_case JSON).
 */
public record ExecutionDto(
    @JsonProperty("query_hash") String queryHash,
    @JsonProperty("normalized_sql") String normalizedSql,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("duration_ms") long durationMs,
    @JsonProperty("row_count") Integer rowCount,
    @JsonProperty("method") String method
) {}
