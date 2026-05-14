package it.berlink.monitoring.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bounded on-disk fallback for batches that could not be delivered to central.
 * One JSON file per batch. When the total size exceeds the configured bound,
 * the oldest files are dropped (and counted) so the spool never grows unbounded.
 */
@Slf4j
@Component
public class DiskSpool {

    private final ObjectMapper objectMapper;
    private final Path spoolDir;
    private final long maxBytes;
    private final AtomicLong droppedBatches = new AtomicLong(0);

    public DiskSpool(
            ObjectMapper objectMapper,
            @Value("${flowcenter.spool.dir}") String spoolDir,
            @Value("${flowcenter.spool.max.bytes:104857600}") long maxBytes) {
        this.objectMapper = objectMapper;
        this.spoolDir = Paths.get(spoolDir);
        this.maxBytes = maxBytes;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(spoolDir);
        log.info("Disk spool at {} (max {} bytes)", spoolDir, maxBytes);
    }

    public synchronized void write(IngestBatch batch) {
        try {
            enforceBound();
            Path file = spoolDir.resolve(System.currentTimeMillis() + "-" + batch.batchId() + ".json");
            Files.writeString(file, objectMapper.writeValueAsString(batch), StandardCharsets.UTF_8);
            log.debug("Spooled batch {} ({} execs)", batch.batchId(), batch.executions().size());
        } catch (IOException e) {
            log.error("Failed to spool batch {}: {}", batch.batchId(), e.getMessage());
        }
    }

    private void enforceBound() throws IOException {
        List<Path> files = list();
        long total = 0;
        for (Path f : files) {
            total += Files.size(f);
        }
        int i = 0;
        while (total > maxBytes && i < files.size()) {
            Path oldest = files.get(i++);
            long size = Files.size(oldest);
            if (Files.deleteIfExists(oldest)) {
                total -= size;
                droppedBatches.incrementAndGet();
                log.warn("Spool over {} bytes — dropped oldest batch file {}", maxBytes, oldest.getFileName());
            }
        }
    }

    public synchronized List<Path> list() throws IOException {
        if (!Files.exists(spoolDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(spoolDir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public IngestBatch read(Path file) throws IOException {
        return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), IngestBatch.class);
    }

    public synchronized void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    public long getDroppedBatches() {
        return droppedBatches.get();
    }

    public int getPendingBatches() {
        try {
            return list().size();
        } catch (IOException e) {
            return -1;
        }
    }
}
