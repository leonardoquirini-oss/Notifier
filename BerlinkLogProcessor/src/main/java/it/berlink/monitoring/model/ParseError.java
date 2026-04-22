package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single parse error captured while reading the log file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseError {

    private Instant timestamp;
    private String line;
}
