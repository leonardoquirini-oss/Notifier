package it.berlink.monitoring.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.berlink.monitoring.model.ProcessorStatus;
import it.berlink.monitoring.service.QueryLogFileProcessor;
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
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Periodically pushes a liveness + progress snapshot to FlowCenter central.
 * Independent of ingestion: keeps reporting even when the log file is idle, so
 * central can tell "collector alive but quiet" from "collector dead".
 */
@Slf4j
@Component
public class HeartbeatClient {

    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(daemonThread("flowcenter-heartbeat"));

    private final QueryLogFileProcessor fileProcessor;
    private final DiskSpool spool;
    private final ObjectMapper objectMapper;
    private final String collectorId;
    private final String statusUrl;
    private final String token;
    private final long intervalMs;

    public HeartbeatClient(
            QueryLogFileProcessor fileProcessor,
            DiskSpool spool,
            ObjectMapper objectMapper,
            @Value("${flowcenter.collector.id}") String collectorId,
            @Value("${flowcenter.central.url}") String centralUrl,
            @Value("${flowcenter.api.token}") String token,
            @Value("${flowcenter.heartbeat.interval.ms:30000}") long intervalMs) {
        this.fileProcessor = fileProcessor;
        this.spool = spool;
        this.objectMapper = objectMapper;
        this.collectorId = collectorId;
        this.statusUrl = centralUrl.replaceAll("/+$", "") + "/api/collectors/status";
        this.token = token;
        this.intervalMs = intervalMs;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(
            this::send, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("HeartbeatClient started, every {}ms to {}", intervalMs, statusUrl);
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
    }

    private void send() {
        try {
            ProcessorStatus status = fileProcessor.getStatus();
            HeartbeatDto dto = new HeartbeatDto(
                collectorId,
                status.getLogFilePath(),
                status.isFileExists(),
                status.getCurrentFilePosition(),
                status.getFileSizeBytes(),
                status.getLinesProcessed(),
                status.getEntriesParsed(),
                status.getParseErrors(),
                spool.getPendingBatches(),
                spool.getDroppedBatches(),
                status.getStartTime() != null ? status.getStartTime().toString() : null,
                status.isRunning());

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(statusUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(dto), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Heartbeat returned {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    private static ThreadFactory daemonThread(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
