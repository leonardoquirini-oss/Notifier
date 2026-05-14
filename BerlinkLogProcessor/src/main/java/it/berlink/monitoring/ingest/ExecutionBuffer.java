package it.berlink.monitoring.ingest;

import it.berlink.monitoring.model.ExecutionPoint;
import it.berlink.monitoring.parser.QueryLogParser.ParsedLogEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Accumulates parsed executions and flushes them to {@link IngestClient} in
 * batches — either when {@code batch.size} is reached or every
 * {@code flush.interval.ms}. Network delivery runs on this buffer's own thread
 * so the log-tailing thread is never blocked on HTTP.
 */
@Slf4j
@Component
public class ExecutionBuffer {

    private final IngestClient ingestClient;
    private final int batchSize;
    private final long flushIntervalMs;
    private final String collectorId;

    private final List<ExecutionDto> buffer = new ArrayList<>();
    private final Object bufferLock = new Object();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(daemonThread("flowcenter-ingest-flush"));

    public ExecutionBuffer(
            IngestClient ingestClient,
            @Value("${flowcenter.ingest.batch.size:200}") int batchSize,
            @Value("${flowcenter.ingest.flush.interval.ms:5000}") long flushIntervalMs,
            @Value("${flowcenter.collector.id}") String collectorId) {
        this.ingestClient = ingestClient;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.collectorId = collectorId;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(
            this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        log.info("ExecutionBuffer started (collector={}, batchSize={}, flushIntervalMs={})",
            collectorId, batchSize, flushIntervalMs);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flush(); // best-effort final flush (spools if central unreachable)
    }

    /**
     * Adds a parsed entry. Triggers an async flush when the batch size is hit.
     */
    public void add(ParsedLogEntry entry) {
        ExecutionPoint ep = entry.executionPoint();
        ExecutionDto dto = new ExecutionDto(
            entry.queryHash(),
            entry.normalizedQuery(),
            (ep.getTimestamp() != null ? ep.getTimestamp() : Instant.now()).toString(),
            ep.getDurationMs(),
            ep.getRowCount(),
            ep.getMethod());

        List<ExecutionDto> toFlush = null;
        synchronized (bufferLock) {
            buffer.add(dto);
            if (buffer.size() >= batchSize) {
                toFlush = drainLocked();
            }
        }
        if (toFlush != null) {
            List<ExecutionDto> batch = toFlush;
            scheduler.execute(() -> sendBatch(batch));
        }
    }

    private void flush() {
        List<ExecutionDto> toFlush;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) {
                return;
            }
            toFlush = drainLocked();
        }
        sendBatch(toFlush);
    }

    private List<ExecutionDto> drainLocked() {
        List<ExecutionDto> copy = new ArrayList<>(buffer);
        buffer.clear();
        return copy;
    }

    private void sendBatch(List<ExecutionDto> executions) {
        IngestBatch batch = new IngestBatch(collectorId, UUID.randomUUID().toString(), executions);
        ingestClient.send(batch);
    }

    private static ThreadFactory daemonThread(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
