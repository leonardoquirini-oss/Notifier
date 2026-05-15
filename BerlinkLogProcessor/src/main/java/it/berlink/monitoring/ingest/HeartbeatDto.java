package it.berlink.monitoring.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Collector liveness + progress snapshot sent to FlowCenter central.
 * Field names map to the central /api/collectors/status contract.
 */
public record HeartbeatDto(
    @JsonProperty("collector_id") String collectorId,
    @JsonProperty("category") String category,
    @JsonProperty("log_file_path") String logFilePath,
    @JsonProperty("file_exists") boolean fileExists,
    @JsonProperty("current_position") long currentPosition,
    @JsonProperty("file_size_bytes") long fileSizeBytes,
    @JsonProperty("lines_processed") long linesProcessed,
    @JsonProperty("entries_parsed") long entriesParsed,
    @JsonProperty("parse_errors") long parseErrors,
    @JsonProperty("spool_pending") int spoolPending,
    @JsonProperty("spool_dropped") long spoolDropped,
    @JsonProperty("started_at") String startedAt,
    @JsonProperty("is_running") boolean isRunning
) {}
