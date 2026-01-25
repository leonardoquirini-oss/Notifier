package it.berlink.monitoring.controller;

import it.berlink.monitoring.model.MonitorOverview;
import it.berlink.monitoring.model.ProcessorStatus;
import it.berlink.monitoring.model.QueryDetail;
import it.berlink.monitoring.model.QueryMetric;
import it.berlink.monitoring.service.QueryLogFileProcessor;
import it.berlink.monitoring.service.QueryMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for query monitoring metrics.
 */
@RestController
@RequestMapping("/api/query-monitor")
@RequiredArgsConstructor
public class QueryMonitorController {

    private final QueryMonitorService monitorService;
    private final QueryLogFileProcessor fileProcessor;

    /**
     * Returns the slowest queries by P95 duration.
     *
     * @param limit Maximum number of results (default 20)
     * @return List of query metrics sorted by P95 descending
     */
    @GetMapping("/slowest")
    public ResponseEntity<List<QueryMetric>> getSlowestQueries(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(monitorService.getSlowestQueries(limit));
    }

    /**
     * Returns the most frequently executed queries.
     *
     * @param limit Maximum number of results (default 20)
     * @return List of query metrics sorted by execution count descending
     */
    @GetMapping("/most-frequent")
    public ResponseEntity<List<QueryMetric>> getMostFrequentQueries(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(monitorService.getMostFrequentQueries(limit));
    }

    /**
     * Returns detailed information about a specific query.
     *
     * @param hash The query hash
     * @return Query detail including metrics and recent executions
     */
    @GetMapping("/{hash}")
    public ResponseEntity<QueryDetail> getQueryDetail(@PathVariable String hash) {
        return monitorService.getQueryDetail(hash)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns overview statistics for all monitored queries.
     *
     * @return Overview with aggregated statistics
     */
    @GetMapping("/overview")
    public ResponseEntity<MonitorOverview> getOverview() {
        return ResponseEntity.ok(monitorService.getOverview());
    }

    /**
     * Returns the current status of the log file processor.
     *
     * @return Processor status including file position and statistics
     */
    @GetMapping("/processor/status")
    public ResponseEntity<ProcessorStatus> getProcessorStatus() {
        return ResponseEntity.ok(fileProcessor.getStatus());
    }
}
