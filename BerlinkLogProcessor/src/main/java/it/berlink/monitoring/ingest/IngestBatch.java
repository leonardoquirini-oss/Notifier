package it.berlink.monitoring.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A batch of executions posted to FlowCenter central in one request.
 * {@code batchId} is the idempotency key — a re-sent batch is skipped server-side.
 */
public record IngestBatch(
    @JsonProperty("collector_id") String collectorId,
    @JsonProperty("batch_id") String batchId,
    @JsonProperty("executions") List<ExecutionDto> executions
) {}
