package it.berlink.monitoring.service;

import it.berlink.monitoring.model.ProcessorStatus;
import it.berlink.monitoring.parser.QueryLogParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that continuously reads and processes a log file.
 * Supports log rotation by detecting file truncation/replacement.
 * Persists read position to Valkey for fault tolerance.
 */
@Slf4j
@Service
public class QueryLogFileProcessor {

    private static final String POSITION_KEY = "logprocessor:position";
    private static final String INODE_KEY = "logprocessor:inode";

    private final QueryLogParser parser;
    private final QueryMonitorService monitorService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String logFilePath;
    private final long pollIntervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong linesProcessed = new AtomicLong(0);
    private final AtomicLong entriesParsed = new AtomicLong(0);
    private final AtomicLong parseErrors = new AtomicLong(0);
    private final Instant startTime = Instant.now();

    private volatile long currentPosition = 0;
    private volatile Instant lastReadTime = null;
    private volatile long lastKnownSize = 0;

    public QueryLogFileProcessor(
            QueryLogParser parser,
            QueryMonitorService monitorService,
            RedisTemplate<String, Object> redisTemplate,
            @Value("${query.log.file.path}") String logFilePath,
            @Value("${query.monitor.poll.interval.ms:1000}") long pollIntervalMs) {
        this.parser = parser;
        this.monitorService = monitorService;
        this.redisTemplate = redisTemplate;
        this.logFilePath = logFilePath;
        this.pollIntervalMs = pollIntervalMs;
    }

    @PostConstruct
    public void start() {
        log.info("Starting QueryLogFileProcessor for file: {}", logFilePath);

        // Restore position from Redis
        restorePosition();

        running.set(true);
        scheduler.scheduleWithFixedDelay(
            this::processLogFile,
            0,
            pollIntervalMs,
            TimeUnit.MILLISECONDS
        );

        log.info("QueryLogFileProcessor started, polling every {}ms", pollIntervalMs);
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping QueryLogFileProcessor...");
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("QueryLogFileProcessor stopped");
    }

    private void processLogFile() {
        if (!running.get()) {
            return;
        }

        Path path = Paths.get(logFilePath);

        try {
            if (!Files.exists(path)) {
                log.debug("Log file does not exist yet: {}", logFilePath);
                return;
            }

            long fileSize = Files.size(path);

            // Detect log rotation (file truncated or replaced)
            if (fileSize < currentPosition) {
                log.info("Log rotation detected (size {} < position {}), resetting to beginning",
                    fileSize, currentPosition);
                currentPosition = 0;
                savePosition();
            }

            // Check if there's new content
            if (fileSize == currentPosition) {
                return;
            }

            lastKnownSize = fileSize;

            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(currentPosition);

                String line;
                while ((line = raf.readLine()) != null && running.get()) {
                    linesProcessed.incrementAndGet();

                    // Handle potential UTF-8 encoding issues from readLine()
                    final String currentLine = new String(line.getBytes("ISO-8859-1"), "UTF-8");

                    parser.parse(currentLine).ifPresentOrElse(
                        entry -> {
                            monitorService.recordExecution(
                                entry.queryHash(),
                                entry.normalizedQuery(),
                                entry.executionPoint()
                            );
                            entriesParsed.incrementAndGet();
                        },
                        () -> {
                            if (!currentLine.isBlank()) {
                                parseErrors.incrementAndGet();
                            }
                        }
                    );

                    currentPosition = raf.getFilePointer();
                }

                lastReadTime = Instant.now();
                savePosition();
            }

        } catch (IOException e) {
            log.error("Error reading log file: {}", e.getMessage());
        }
    }

    private void restorePosition() {
        try {
            Object posObj = redisTemplate.opsForValue().get(POSITION_KEY);
            if (posObj instanceof Number) {
                currentPosition = ((Number) posObj).longValue();
                log.info("Restored position from Redis: {}", currentPosition);
            }
        } catch (Exception e) {
            log.warn("Failed to restore position from Redis: {}", e.getMessage());
            currentPosition = 0;
        }
    }

    private void savePosition() {
        try {
            redisTemplate.opsForValue().set(POSITION_KEY, currentPosition, Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("Failed to save position to Redis: {}", e.getMessage());
        }
    }

    /**
     * Returns the current status of the processor.
     */
    public ProcessorStatus getStatus() {
        Path path = Paths.get(logFilePath);
        boolean exists = Files.exists(path);
        long fileSize = 0;

        if (exists) {
            try {
                fileSize = Files.size(path);
            } catch (IOException ignored) {
            }
        }

        return ProcessorStatus.builder()
            .logFilePath(logFilePath)
            .fileExists(exists)
            .currentFilePosition(currentPosition)
            .fileSizeBytes(fileSize)
            .linesProcessed(linesProcessed.get())
            .entriesParsed(entriesParsed.get())
            .parseErrors(parseErrors.get())
            .startTime(startTime)
            .lastReadTime(lastReadTime)
            .isRunning(running.get())
            .build();
    }
}
