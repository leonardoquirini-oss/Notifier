package it.berlink.monitoring.service;

import it.berlink.monitoring.ingest.ExecutionBuffer;
import it.berlink.monitoring.model.ParseError;
import it.berlink.monitoring.model.ProcessorStatus;
import it.berlink.monitoring.parser.QueryLogParser;
import it.berlink.position.FilePositionStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Service that continuously reads and processes a log file.
 * Supports log rotation by detecting file truncation/replacement.
 * Persists read position to a local file for fault tolerance and hands parsed
 * executions to {@link ExecutionBuffer} for delivery to FlowCenter central.
 */
@Slf4j
@Service
public class QueryLogFileProcessor {

    private static final int MAX_PARSE_ERRORS = 200;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_BUFFERED_LINES = 500;

    // A new log record starts with timestamp + log level
    private static final Pattern LOG_RECORD_START = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?\\s+\\w+\\s+"
    );

    // End of a JSON ActiveJDBC payload: ..."duration_millis":<n>} or ..."duration_millis":<n>,"cache":"<v>"}
    private static final Pattern JSON_RECORD_END = Pattern.compile(
        "\"duration_millis\"\\s*:\\s*\\d+(\\s*,\\s*\"cache\"\\s*:\\s*\"[^\"]*\")?\\s*}\\s*$"
    );

    private final QueryLogParser parser;
    private final ExecutionBuffer executionBuffer;
    private final FilePositionStore positionStore;
    private final String logFilePath;
    private final long pollIntervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong linesProcessed = new AtomicLong(0);
    private final AtomicLong entriesParsed = new AtomicLong(0);
    private final AtomicLong parseErrors = new AtomicLong(0);
    private final Instant startTime = Instant.now();
    private final Deque<ParseError> parseErrorBuffer = new ArrayDeque<>(MAX_PARSE_ERRORS);

    private volatile long currentPosition = 0;
    private volatile Instant lastReadTime = null;
    private volatile long lastKnownSize = 0;

    // Multi-line record accumulator (SQL queries can span many lines)
    private final StringBuilder recordBuffer = new StringBuilder();
    private int recordBufferLines = 0;

    public QueryLogFileProcessor(
            QueryLogParser parser,
            ExecutionBuffer executionBuffer,
            FilePositionStore positionStore,
            @Value("${query.log.file.path}") String logFilePath,
            @Value("${query.monitor.poll.interval.ms:1000}") long pollIntervalMs) {
        this.parser = parser;
        this.executionBuffer = executionBuffer;
        this.positionStore = positionStore;
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

                    handleLogLine(currentLine);

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
        currentPosition = positionStore.restore();
    }

    private void savePosition() {
        positionStore.save(currentPosition);
    }

    /**
     * Accumulates log lines into a single logical record, handling SQL queries
     * that span multiple lines. A record starts when a line matches the
     * timestamp+level prefix and ends when the JSON payload closes with
     * "duration_millis":<n>(,"cache":"...")?} — or when a new record starts.
     */
    private void handleLogLine(String line) {
        if (line == null) {
            return;
        }

        boolean startsNewRecord = LOG_RECORD_START.matcher(line).find();

        if (startsNewRecord) {
            // Flush any pending record first
            if (recordBuffer.length() > 0) {
                flushRecordBuffer();
            }
            recordBuffer.append(line);
            recordBufferLines = 1;
        } else {
            if (recordBuffer.length() == 0) {
                // Orphan continuation line with no active record: treat as parse error
                if (!line.isBlank()) {
                    parseErrors.incrementAndGet();
                    recordParseError(line);
                }
                return;
            }
            // Continuation of current record — preserve newline for regex-friendly join
            recordBuffer.append('\n').append(line);
            recordBufferLines++;
        }

        // If current accumulated record ends with the JSON terminator, flush now
        if (JSON_RECORD_END.matcher(recordBuffer).find()) {
            flushRecordBuffer();
            return;
        }

        // Safety net: avoid unbounded growth on malformed records
        if (recordBufferLines > MAX_BUFFERED_LINES) {
            log.warn("Record buffer exceeded {} lines without terminator, flushing as error", MAX_BUFFERED_LINES);
            flushRecordBuffer();
        }
    }

    private void flushRecordBuffer() {
        if (recordBuffer.length() == 0) {
            return;
        }
        // Collapse the multi-line record into a single line so the existing
        // single-line regex-based parser can match it.
        String record = recordBuffer.toString().replaceAll("\\s*\\r?\\n\\s*", " ");
        recordBuffer.setLength(0);
        recordBufferLines = 0;

        parser.parse(record).ifPresentOrElse(
            entry -> {
                executionBuffer.add(entry);
                entriesParsed.incrementAndGet();
            },
            () -> {
                if (!record.isBlank()) {
                    parseErrors.incrementAndGet();
                    recordParseError(record);
                }
            }
        );
    }

    private void recordParseError(String line) {
        String truncated = line.length() > MAX_LINE_LENGTH
            ? line.substring(0, MAX_LINE_LENGTH) + "…"
            : line;
        ParseError error = ParseError.builder()
            .timestamp(Instant.now())
            .line(truncated)
            .build();
        synchronized (parseErrorBuffer) {
            if (parseErrorBuffer.size() >= MAX_PARSE_ERRORS) {
                parseErrorBuffer.pollFirst();
            }
            parseErrorBuffer.offerLast(error);
        }
    }

    /**
     * Returns the most recent parse errors, newest first.
     *
     * @param limit Maximum number of errors to return; if &lt;= 0, returns all buffered.
     */
    public List<ParseError> getRecentParseErrors(int limit) {
        synchronized (parseErrorBuffer) {
            List<ParseError> snapshot = new ArrayList<>(parseErrorBuffer);
            java.util.Collections.reverse(snapshot);
            if (limit > 0 && snapshot.size() > limit) {
                return new ArrayList<>(snapshot.subList(0, limit));
            }
            return snapshot;
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
