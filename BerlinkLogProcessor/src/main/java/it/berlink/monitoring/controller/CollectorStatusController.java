package it.berlink.monitoring.controller;

import it.berlink.monitoring.ingest.DiskSpool;
import it.berlink.monitoring.model.ParseError;
import it.berlink.monitoring.model.ProcessorStatus;
import it.berlink.monitoring.service.QueryLogFileProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operational status of the collector: log file processing and ingest spool.
 * Query metrics now live in FlowCenter central — this collector only ships data.
 */
@RestController
@RequestMapping("/api/collector")
@RequiredArgsConstructor
public class CollectorStatusController {

    private final QueryLogFileProcessor fileProcessor;
    private final DiskSpool spool;

    /**
     * Log file processor runtime status (position, lines, parse errors).
     */
    @GetMapping("/status")
    public ResponseEntity<ProcessorStatus> getStatus() {
        return ResponseEntity.ok(fileProcessor.getStatus());
    }

    /**
     * Most recent parse failures, newest first.
     */
    @GetMapping("/parse-errors")
    public ResponseEntity<List<ParseError>> getParseErrors(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(fileProcessor.getRecentParseErrors(limit));
    }

    /**
     * Ingest spool state: batches waiting for central, and batches dropped
     * because the spool hit its size bound.
     */
    @GetMapping("/ingest")
    public ResponseEntity<IngestStatus> getIngestStatus() {
        return ResponseEntity.ok(new IngestStatus(
            spool.getPendingBatches(),
            spool.getDroppedBatches()));
    }

    public record IngestStatus(int pendingBatches, long droppedBatches) {}
}
