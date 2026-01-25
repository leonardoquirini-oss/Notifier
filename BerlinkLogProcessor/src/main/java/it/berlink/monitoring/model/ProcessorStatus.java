package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Status information about the log file processor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessorStatus {

    private String logFilePath;
    private boolean fileExists;
    private long currentFilePosition;
    private long fileSizeBytes;
    private long linesProcessed;
    private long entriesParsed;
    private long parseErrors;
    private Instant startTime;
    private Instant lastReadTime;
    private boolean isRunning;
}
