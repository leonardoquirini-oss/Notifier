package it.berlink.monitoring.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.berlink.monitoring.model.ExecutionPoint;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses log entries containing SQL query execution information.
 *
 * Supports two formats:
 *
 * 1. Legacy pipe-delimited format:
 *    2025-01-24 10:15:32.456 | SELECT * FROM users WHERE id = ? | 45ms | rows:1
 *
 * 2. ActiveJDBC JSON format:
 *    2026-01-25 16:55:10.891 INFO  [823552] [OperationService.getAllOperations] org.javalite.activejdbc.LazyList - {"sql":"SELECT * FROM gb_site_bulks WHERE id_site = ?","params":[4],"duration_millis":1,"cache":"miss"}
 */
@Slf4j
@Component
public class QueryLogParser {

    // Legacy pipe-delimited format
    private static final Pattern LEGACY_LOG_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)\\s*\\|\\s*(.+?)\\s*\\|\\s*(\\d+)\\s*ms\\s*(?:\\|\\s*rows?:\\s*(\\d+))?$"
    );

    // ActiveJDBC JSON format:
    // TIMESTAMP LEVEL [THREAD] [METHOD] LOGGER - JSON
    // Example: 2026-01-25 20:54:36.229 INFO  [401519] [OperationService.getAllOperations] org.javalite.activejdbc.LazyList - {"sql":"...","duration_millis":0,...}
    private static final Pattern ACTIVEJDBC_LOG_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})" +  // timestamp (group 1)
        "\\s+\\w+" +                                                // log level (INFO, DEBUG, etc)
        "\\s+\\[\\d+\\]" +                                          // thread id [401519]
        "\\s+\\[([^\\]]+)\\]" +                                     // method (group 2)
        ".* - " +                                                   // logger name + separator
        "(\\{.+\\})$"                                               // JSON payload (group 3)
    );

    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    };

    private final ObjectMapper objectMapper;

    public QueryLogParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Represents a parsed log entry.
     */
    public record ParsedLogEntry(
        String queryHash,
        String normalizedQuery,
        ExecutionPoint executionPoint
    ) {}

    /**
     * Parses a single log line into a ParsedLogEntry.
     * Tries ActiveJDBC format first, then falls back to legacy format.
     *
     * @param line The log line to parse
     * @return Optional containing the parsed entry, or empty if parsing fails
     */
    public Optional<ParsedLogEntry> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        String trimmedLine = line.trim();

        // Try ActiveJDBC format first (most common in this project)
        Optional<ParsedLogEntry> result = parseActiveJdbcFormat(trimmedLine);
        if (result.isPresent()) {
            return result;
        }

        // Fall back to legacy format
        return parseLegacyFormat(trimmedLine);
    }

    /**
     * Parses ActiveJDBC JSON format log entries.
     */
    private Optional<ParsedLogEntry> parseActiveJdbcFormat(String line) {

        log.trace("Line to be parsed: {} - pattern {}", line, ACTIVEJDBC_LOG_PATTERN);

        Matcher matcher = ACTIVEJDBC_LOG_PATTERN.matcher(line);
        if (!matcher.matches()) {
            log.trace("No Match!");
            return Optional.empty();
        }

        try {
            String timestampStr = matcher.group(1);
            String method = matcher.group(2);
            String jsonPayload = matcher.group(3);

            // Parse JSON payload
            JsonNode jsonNode = objectMapper.readTree(jsonPayload);

            String sql = jsonNode.path("sql").asText(null);
            if (sql == null || sql.isBlank()) {
                log.trace("No SQL found in JSON payload: {}", jsonPayload);
                return Optional.empty();
            }

            long durationMs = jsonNode.path("duration_millis").asLong(0);

            Instant timestamp = parseTimestamp(timestampStr);
            String normalizedQuery = normalizeQuery(sql);
            String queryHash = computeHash(normalizedQuery);

            ExecutionPoint executionPoint = ExecutionPoint.builder()
                .timestamp(timestamp)
                .durationMs(durationMs)
                .rowCount(0) // Not available in this format
                .method(method)
                .build();

            return Optional.of(new ParsedLogEntry(queryHash, normalizedQuery, executionPoint));

        } catch (JsonProcessingException e) {
            log.trace("Failed to parse JSON in ActiveJDBC log: {} - Error: {}", line, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse ActiveJDBC log line: {} - Error: {}", line, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses legacy pipe-delimited format log entries.
     */
    private Optional<ParsedLogEntry> parseLegacyFormat(String line) {
        Matcher matcher = LEGACY_LOG_PATTERN.matcher(line);
        if (!matcher.matches()) {
            log.trace("Line does not match expected format: {}", line);
            return Optional.empty();
        }

        try {
            String timestampStr = matcher.group(1);
            String query = matcher.group(2).trim();
            long durationMs = Long.parseLong(matcher.group(3));
            int rowCount = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 0;

            Instant timestamp = parseTimestamp(timestampStr);
            String normalizedQuery = normalizeQuery(query);
            String queryHash = computeHash(normalizedQuery);

            ExecutionPoint executionPoint = ExecutionPoint.builder()
                .timestamp(timestamp)
                .durationMs(durationMs)
                .rowCount(rowCount)
                .method(null) // Not available in legacy format
                .build();

            return Optional.of(new ParsedLogEntry(queryHash, normalizedQuery, executionPoint));

        } catch (Exception e) {
            log.warn("Failed to parse legacy log line: {} - Error: {}", line, e.getMessage());
            return Optional.empty();
        }
    }

    private Instant parseTimestamp(String timestampStr) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(timestampStr, formatter);
                return ldt.atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try next formatter
            }
        }
        throw new IllegalArgumentException("Unable to parse timestamp: " + timestampStr);
    }

    /**
     * Normalizes a SQL query by replacing literal values with placeholders.
     * This allows grouping similar queries together.
     */
    String normalizeQuery(String query) {
        String normalized = query
            // Remove extra whitespace
            .replaceAll("\\s+", " ")
            // Replace numeric literals
            .replaceAll("\\b\\d+\\b", "?")
            // Replace string literals (single quotes)
            .replaceAll("'[^']*'", "?")
            // Replace string literals (double quotes for identifiers that might contain values)
            .replaceAll("\"[^\"]*\"", "?")
            // Replace IN clause values
            .replaceAll("\\bIN\\s*\\(\\s*[^)]+\\s*\\)", "IN (?)")
            // Replace VALUES clause
            .replaceAll("\\bVALUES\\s*\\([^)]+\\)", "VALUES (?)")
            // Normalize case for SQL keywords
            .toUpperCase()
            .trim();

        return normalized;
    }

    /**
     * Computes an MD5 hash of the normalized query.
     */
    String computeHash(String normalizedQuery) {
        return DigestUtils.md5Hex(normalizedQuery).substring(0, 16);
    }
}
