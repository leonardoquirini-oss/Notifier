package it.berlink.position;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Persists the log file read position to a local file on the collector's state
 * volume. Replaces the previous Valkey-backed position store — the collector no
 * longer depends on Valkey for fault tolerance.
 */
@Slf4j
@Component
public class FilePositionStore {

    private final Path positionFile;

    public FilePositionStore(@Value("${flowcenter.position.file}") String positionFile) {
        this.positionFile = Paths.get(positionFile);
    }

    @PostConstruct
    public void init() throws IOException {
        Path parent = positionFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        log.info("Position file: {}", positionFile);
    }

    public long restore() {
        try {
            if (Files.exists(positionFile)) {
                String content = Files.readString(positionFile, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    long position = Long.parseLong(content);
                    log.info("Restored position from file: {}", position);
                    return position;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to restore position from file: {}", e.getMessage());
        }
        return 0;
    }

    public void save(long position) {
        Path tmp = positionFile.resolveSibling(positionFile.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, Long.toString(position), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, positionFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, positionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to save position to file: {}", e.getMessage());
        }
    }
}
