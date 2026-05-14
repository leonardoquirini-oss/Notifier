package it.berlink.monitoring.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Ships batches to FlowCenter central over HTTP. On delivery failure a batch is
 * written to {@link DiskSpool}; a background task drains the spool when central
 * recovers. The same {@code batchId} is reused on retry so central can dedup.
 */
@Slf4j
@Component
public class IngestClient {

    // HTTP/1.1 explicitly: the default HTTP/2 client attempts an h2c upgrade
    // over cleartext, which plain ASGI servers (uvicorn) reject as a bad request.
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ScheduledExecutorService retryScheduler =
        Executors.newSingleThreadScheduledExecutor(daemonThread("flowcenter-ingest-retry"));

    private final ObjectMapper objectMapper;
    private final DiskSpool spool;
    private final String ingestUrl;
    private final String token;
    private final long retryIntervalMs;

    public IngestClient(
            ObjectMapper objectMapper,
            DiskSpool spool,
            @Value("${flowcenter.central.url}") String centralUrl,
            @Value("${flowcenter.api.token}") String token,
            @Value("${flowcenter.ingest.retry.interval.ms:30000}") long retryIntervalMs) {
        this.objectMapper = objectMapper;
        this.spool = spool;
        this.ingestUrl = centralUrl.replaceAll("/+$", "") + "/api/ingest/queries";
        this.token = token;
        this.retryIntervalMs = retryIntervalMs;
    }

    @PostConstruct
    public void start() {
        if (token == null || token.isBlank()) {
            log.error("flowcenter.api.token is empty — ingestion will be rejected (401) by central");
        }
        log.info("IngestClient target: {}", ingestUrl);
        retryScheduler.scheduleWithFixedDelay(
            this::drainSpool, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThread(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    public void stop() {
        retryScheduler.shutdown();
        try {
            if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Delivers a batch, spooling it to disk if delivery fails.
     */
    public void send(IngestBatch batch) {
        if (!postBatch(batch)) {
            spool.write(batch);
        }
    }

    /**
     * Attempts one network delivery. Returns true only on a 200 response.
     */
    private boolean postBatch(IngestBatch batch) {
        try {
            String body = objectMapper.writeValueAsString(batch);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ingestUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                log.debug("Batch {} delivered ({} execs)", batch.batchId(), batch.executions().size());
                return true;
            }
            if (status == 401) {
                log.error("Ingest auth failed (401) — check flowcenter.api.token");
            } else {
                log.warn("Ingest returned {} for batch {}", status, batch.batchId());
            }
            return false;
        } catch (Exception e) {
            log.warn("Ingest delivery failed for batch {}: {}", batch.batchId(), e.getMessage());
            return false;
        }
    }

    /**
     * Resends spooled batches oldest-first. Stops at the first failure so it
     * does not hammer a central that is still down.
     */
    private void drainSpool() {
        try {
            List<Path> files = spool.list();
            if (files.isEmpty()) {
                return;
            }
            log.info("Draining spool: {} pending batches", files.size());
            for (Path file : files) {
                IngestBatch batch;
                try {
                    batch = spool.read(file);
                } catch (Exception e) {
                    log.error("Corrupt spool file {} — deleting: {}", file.getFileName(), e.getMessage());
                    spool.delete(file);
                    continue;
                }
                if (postBatch(batch)) {
                    spool.delete(file);
                } else {
                    log.info("Central still unreachable — stopping drain, {} batches remain", file.getFileName());
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Spool drain failed: {}", e.getMessage());
        }
    }
}
