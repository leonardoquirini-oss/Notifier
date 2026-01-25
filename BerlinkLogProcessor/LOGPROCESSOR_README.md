# Query Monitor System - Implementation Guide

## Overview

A real-time query monitoring system for Spring Boot applications that processes SQL query logs and provides performance analytics using Valkey (Redis fork). The system reads query execution data from log files and stores metrics in Valkey with configurable retention periods.

## Architecture

```
Log Files → File Processor → Valkey → REST API → Dashboard
                ↓
         Query Metrics
         (P50, P95, P99)
```

### Key Features

- **Zero application impact**: Reads from existing log files
- **Real-time monitoring**: Dashboard updates every 5 seconds
- **Configurable retention**: Default 15 days, fully configurable
- **Automatic cleanup**: TTL-based expiration in Valkey
- **Log rotation support**: Handles rotated log files automatically
- **Fault tolerant**: Resumes from last processed position after restart
- **Project Brain**: Use CLAUDE.md as reference file

## Project Structure

```
src/main/java/it/berlink/
├── monitoring/
│   ├── service/
│   │   ├── QueryMonitorService.java
│   │   └── QueryLogFileProcessor.java
│   ├── parser/
│   │   └── QueryLogParser.java
│   ├── controller/
│   │   └── QueryMonitorController.java
│   └── model/
│       ├── QueryMetric.java
│       ├── QueryDetail.java
│       ├── ExecutionPoint.java
│       ├── MonitorOverview.java
│       └── ProcessorStatus.java
├── config/
│   └── RedisConfig.java
```

## Data Model in Valkey

### Keys Structure

1. **Query Details** (String)
   - Key: `query:details:{hash}`
   - Value: Normalized query text
   - TTL: Configurable (default 15 days)

2. **Query Executions** (Sorted Set)
   - Key: `query:executions:{hash}`
   - Members: Duration in ms (as string)
   - Score: Timestamp (for time-series data)
   - TTL: Configurable

3. **Query Statistics** (Hash)
   - Key: `query:stats:{hash}`
   - Fields:
     - `count`: Total execution count
     - `total_time`: Sum of all execution times
     - `min`: Minimum execution time
     - `max`: Maximum execution time
     - `last_seen`: Last execution timestamp
   - TTL: Configurable

4. **Slow Queries Ranking** (Sorted Set)
   - Key: `query:slow:sorted`
   - Members: Query hash
   - Score: P95 execution time
   - TTL: Configurable

5. **Processor State** (Hash)
   - Key: `query:processor:state`
   - Fields:
     - `position`: Last file position read
     - `filename`: Last file processed
     - `last_update`: Last processing timestamp

6. **File Processing State** (Hash)
   - Key: `query:processor:files:{file_hash}`
   - Fields:
     - `position`: Last position in this file
     - `last_update`: Last update timestamp
   - TTL: 30 days

## Implementation Steps

### Step 1: Dependencies

Add to `pom.xml`:

```xml
<dependencies>
    <!-- Spring Boot Starter Data Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    
    <!-- Lettuce (Redis client) -->
    <dependency>
        <groupId>io.lettuce</groupId>
        <artifactId>lettuce-core</artifactId>
    </dependency>
    
    <!-- Apache Commons Codec (for MD5 hashing) -->
    <dependency>
        <groupId>commons-codec</groupId>
        <artifactId>commons-codec</artifactId>
    </dependency>
    
    <!-- Lombok (optional, for cleaner code) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### Step 2: Configuration

Create `application.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5

query:
  log:
    file:
      # Single file mode
      path: /berlink/logs/berlink_queries.log
      # OR multi-file pattern for log rotation
      pattern: /berlink/logs/berlink*.log
    processor:
      interval: 5000  # Check for new entries every 5 seconds
  monitor:
    retention:
      days: 15  # Keep data for 15 days
    slow:
      threshold: 500  # Queries over 500ms are considered slow
```

### Step 3: Redis Configuration Class

File: `src/main/java/it/berlink/config/RedisConfig.java`

```java
package it.berlink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

### Step 4: Model Classes

File: `src/main/java/it/berlink/monitoring/model/QueryMetric.java`

```java
package it.berlink.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryMetric {
    private String hash;
    private String query;
    private long count;
    private long avgTime;
    private long minTime;
    private long maxTime;
    private long p95Time;
    private long lastSeen;
}
```

File: `src/main/java/it/berlink/monitoring/model/QueryDetail.java`

```java
package it.berlink.monitoring.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class QueryDetail {
    private String hash;
    private String query;
    private long count;
    private long avgTime;
    private long minTime;
    private long maxTime;
    private long p95Time;
    private long lastSeen;
    private List<ExecutionPoint> executionHistory;
}
```

File: `src/main/java/it/berlink/monitoring/model/ExecutionPoint.java`

```java
package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ExecutionPoint {
    private long timestamp;
    private int duration;
}
```

File: `src/main/java/it/berlink/monitoring/model/MonitorOverview.java`

```java
package it.berlink.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MonitorOverview {
    private int uniqueQueries;
    private long totalExecutions;
    private long avgExecutionTime;
    private int retentionDays;
}
```

File: `src/main/java/it/berlink/monitoring/model/ProcessorStatus.java`

```java
package it.berlink.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProcessorStatus {
    private boolean healthy;
    private String lastProcessedFile;
    private long lastProcessedPosition;
    private long lastUpdateTime;
    private long secondsSinceLastUpdate;
}
```

### Step 5: Query Log Parser

File: `src/main/java/it/berlink/monitoring/parser/QueryLogParser.java`

```java
package it.berlink.monitoring.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class QueryLogParser {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Supported log formats
    private final List<LogPattern> patterns = List.of(
        // Format 1: 2026-01-25 16:22:36.600 | SELECT * FROM users WHERE id = ? | 145ms
        new LogPattern(
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*\\|\\s*(.+?)\\s*\\|\\s*(\\d+)ms"),
            1, 2, 3
        ),
        // Format 2: [2026-01-25 16:22:36] duration=145ms query=SELECT * FROM users
        new LogPattern(
            Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\].*?duration=(\\d+)ms.*?query=(.+)$"),
            1, 3, 2
        ),
        // Format 3: 2026-01-25 16:22:36 INFO Query: SELECT * FROM users | Time: 145ms
        new LogPattern(
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+\\w+\\s+Query:\\s*(.+?)\\s*\\|\\s*Time:\\s*(\\d+)ms"),
            1, 2, 3
        )
    );
    
    public QueryLogEntry parse(String line) {
        // Try regex patterns
        for (LogPattern pattern : patterns) {
            Matcher matcher = pattern.getPattern().matcher(line);
            if (matcher.find()) {
                try {
                    return new QueryLogEntry(
                        matcher.group(pattern.getQueryGroup()).trim(),
                        Long.parseLong(matcher.group(pattern.getDurationGroup())),
                        matcher.group(pattern.getTimestampGroup())
                    );
                } catch (Exception e) {
                    log.warn("Failed to parse matched line: {}", line, e);
                }
            }
        }
        
        // Try JSON format: {"timestamp":"2026-01-25 16:22:36","query":"SELECT...","duration":145}
        if (line.trim().startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(line);
                if (node.has("query") && node.has("duration")) {
                    return new QueryLogEntry(
                        node.get("query").asText(),
                        node.get("duration").asLong(),
                        node.has("timestamp") ? node.get("timestamp").asText() : null
                    );
                }
            } catch (Exception e) {
                // Not valid JSON
            }
        }
        
        return null;
    }
    
    @Data
    @AllArgsConstructor
    public static class LogPattern {
        private Pattern pattern;
        private int timestampGroup;
        private int queryGroup;
        private int durationGroup;
    }
    
    @Data
    @AllArgsConstructor
    public static class QueryLogEntry {
        private String query;
        private long duration;
        private String timestamp;
    }
}
```

### Step 6: Query Monitor Service

File: `src/main/java/it/berlink/monitoring/service/QueryMonitorService.java`

```java
package it.berlink.monitoring.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class QueryMonitorService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Value("${query.monitor.retention.days:15}")
    private int retentionDays;
    
    @Value("${query.monitor.slow.threshold:500}")
    private int slowThresholdMs;
    
    private static final String QUERY_DETAILS_PREFIX = "query:details:";
    private static final String QUERY_EXECUTIONS_PREFIX = "query:executions:";
    private static final String SLOW_QUERIES_SORTED = "query:slow:sorted";
    private static final String QUERY_STATS_PREFIX = "query:stats:";
    private static final String PROCESSOR_STATE_KEY = "query:processor:state";
    
    public void logQuery(String query, long durationMs) {
        String normalized = normalizeQuery(query);
        String hash = hashQuery(normalized);
        long timestamp = System.currentTimeMillis();
        int retentionSeconds = retentionDays * 24 * 60 * 60;
        
        try {
            // 1. Save query details (if not already present)
            String detailsKey = QUERY_DETAILS_PREFIX + hash;
            Boolean exists = redisTemplate.hasKey(detailsKey);
            if (Boolean.FALSE.equals(exists)) {
                redisTemplate.opsForValue().set(detailsKey, normalized);
                redisTemplate.expire(detailsKey, retentionSeconds, TimeUnit.SECONDS);
            }
            
            // 2. Add execution to time-series (sorted set with timestamp as score)
            String executionsKey = QUERY_EXECUTIONS_PREFIX + hash;
            redisTemplate.opsForZSet().add(executionsKey, 
                String.valueOf(durationMs), 
                timestamp);
            redisTemplate.expire(executionsKey, retentionSeconds, TimeUnit.SECONDS);
            
            // 3. Update aggregate statistics
            updateStats(hash, durationMs, retentionSeconds);
            
            // 4. Update slow queries ranking (by P95)
            updateSlowQueriesRanking(hash, retentionSeconds);
            
            // 5. Cleanup old executions (keep only retention period)
            long cutoffTimestamp = timestamp - (retentionDays * 24L * 60 * 60 * 1000);
            redisTemplate.opsForZSet().removeRangeByScore(executionsKey, 0, cutoffTimestamp);
            
        } catch (Exception e) {
            log.error("Failed to log query metrics", e);
        }
    }
    
    private void updateStats(String hash, long duration, int ttl) {
        String statsKey = QUERY_STATS_PREFIX + hash;
        
        redisTemplate.opsForHash().increment(statsKey, "count", 1);
        redisTemplate.opsForHash().increment(statsKey, "total_time", duration);
        redisTemplate.opsForHash().put(statsKey, "last_seen", 
            String.valueOf(System.currentTimeMillis()));
        
        // Update min
        String minStr = (String) redisTemplate.opsForHash().get(statsKey, "min");
        if (minStr == null || duration < Long.parseLong(minStr)) {
            redisTemplate.opsForHash().put(statsKey, "min", String.valueOf(duration));
        }
        
        // Update max
        String maxStr = (String) redisTemplate.opsForHash().get(statsKey, "max");
        if (maxStr == null || duration > Long.parseLong(maxStr)) {
            redisTemplate.opsForHash().put(statsKey, "max", String.valueOf(duration));
        }
        
        redisTemplate.expire(statsKey, ttl, TimeUnit.SECONDS);
    }
    
    private void updateSlowQueriesRanking(String hash, int ttl) {
        // Calculate P95 from recent executions
        String executionsKey = QUERY_EXECUTIONS_PREFIX + hash;
        Long count = redisTemplate.opsForZSet().size(executionsKey);
        
        if (count != null && count > 0) {
            long p95Index = (long) Math.ceil(count * 0.95) - 1;
            Set<String> p95Set = redisTemplate.opsForZSet()
                .reverseRange(executionsKey, p95Index, p95Index);
            
            if (p95Set != null && !p95Set.isEmpty()) {
                double p95Value = Double.parseDouble(p95Set.iterator().next());
                redisTemplate.opsForZSet().add(SLOW_QUERIES_SORTED, hash, p95Value);
            }
        }
        
        redisTemplate.expire(SLOW_QUERIES_SORTED, ttl, TimeUnit.SECONDS);
    }
    
    private String normalizeQuery(String query) {
        return query.replaceAll("'[^']*'", "?")          // Replace string literals
                   .replaceAll("\\b\\d+\\b", "?")        // Replace numbers
                   .replaceAll("\\s+", " ")              // Normalize whitespace
                   .trim()
                   .toLowerCase();
    }
    
    private String hashQuery(String query) {
        return DigestUtils.md5Hex(query).substring(0, 16);
    }
    
    // Processor state management
    public void saveLastProcessedPosition(long position, String filename) {
        redisTemplate.opsForHash().put(PROCESSOR_STATE_KEY, "position", String.valueOf(position));
        redisTemplate.opsForHash().put(PROCESSOR_STATE_KEY, "filename", filename);
        redisTemplate.opsForHash().put(PROCESSOR_STATE_KEY, "last_update", 
            String.valueOf(System.currentTimeMillis()));
    }
    
    public long getLastProcessedPosition() {
        String pos = (String) redisTemplate.opsForHash().get(PROCESSOR_STATE_KEY, "position");
        return pos != null ? Long.parseLong(pos) : 0L;
    }
    
    public String getLastProcessedFile() {
        return (String) redisTemplate.opsForHash().get(PROCESSOR_STATE_KEY, "filename");
    }
}
```

### Step 7: Log File Processor

File: `src/main/java/it/berlink/monitoring/service/QueryLogFileProcessor.java`

```java
package it.berlink.monitoring.service;

import it.berlink.monitoring.parser.QueryLogParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QueryLogFileProcessor {
    
    @Autowired
    private QueryMonitorService monitorService;
    
    @Autowired
    private QueryLogParser parser;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Value("${query.log.file.path:}")
    private String logFilePath;
    
    @Value("${query.log.file.pattern:}")
    private String logFilePattern;
    
    @PostConstruct
    public void init() {
        if (logFilePath.isEmpty() && logFilePattern.isEmpty()) {
            log.warn("No log file path or pattern configured. Query monitoring disabled.");
        } else {
            log.info("Query log processor initialized. Path: {}, Pattern: {}", 
                logFilePath, logFilePattern);
        }
    }
    
    @Scheduled(fixedDelayString = "${query.log.processor.interval:5000}")
    public void processLogFiles() {
        if (!logFilePattern.isEmpty()) {
            // Multi-file mode (log rotation)
            processMultipleFiles();
        } else if (!logFilePath.isEmpty()) {
            // Single file mode
            processSingleFile();
        }
    }
    
    private void processSingleFile() {
        File logFile = new File(logFilePath);
        
        if (!logFile.exists()) {
            log.debug("Log file not found: {}", logFilePath);
            return;
        }
        
        processFile(logFile);
    }
    
    private void processMultipleFiles() {
        List<File> logFiles = findLogFiles();
        
        for (File logFile : logFiles) {
            processFile(logFile);
        }
    }
    
    private void processFile(File logFile) {
        String fileId = logFile.getAbsolutePath();
        Long lastPosition = getFilePosition(fileId);
        
        if (lastPosition == null) {
            lastPosition = 0L;
        }
        
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long fileLength = raf.length();
            
            // If file is smaller than last position, it was rotated/truncated
            if (fileLength < lastPosition) {
                log.info("File {} appears to have been rotated, resetting position", logFile.getName());
                lastPosition = 0L;
            }
            
            // If file unchanged, skip
            if (fileLength == lastPosition) {
                return;
            }
            
            raf.seek(lastPosition);
            
            String line;
            int processedLines = 0;
            
            while ((line = raf.readLine()) != null) {
                processLogLine(line);
                processedLines++;
            }
            
            // Save new position
            long newPosition = raf.getFilePointer();
            saveFilePosition(fileId, newPosition);
            
            if (processedLines > 0) {
                log.debug("Processed {} lines from {}", processedLines, logFile.getName());
            }
            
        } catch (IOException e) {
            log.error("Error processing log file: {}", logFile, e);
        }
    }
    
    private void processLogLine(String line) {
        try {
            QueryLogParser.QueryLogEntry entry = parser.parse(line);
            if (entry != null) {
                monitorService.logQuery(entry.getQuery(), entry.getDuration());
            }
        } catch (Exception e) {
            log.error("Failed to process log line: {}", line, e);
        }
    }
    
    private List<File> findLogFiles() {
        File logDir = new File(logFilePattern).getParentFile();
        String pattern = new File(logFilePattern).getName().replace("*", ".*");
        
        File[] files = logDir.listFiles((dir, name) -> name.matches(pattern));
        
        if (files == null) {
            return List.of();
        }
        
        // Sort by last modified (oldest first)
        return Arrays.stream(files)
            .sorted(Comparator.comparingLong(File::lastModified))
            .collect(Collectors.toList());
    }
    
    private Long getFilePosition(String fileId) {
        String key = "query:processor:files:" + DigestUtils.md5Hex(fileId);
        String pos = (String) redisTemplate.opsForHash().get(key, "position");
        return pos != null ? Long.parseLong(pos) : null;
    }
    
    private void saveFilePosition(String fileId, long position) {
        String key = "query:processor:files:" + DigestUtils.md5Hex(fileId);
        redisTemplate.opsForHash().put(key, "position", String.valueOf(position));
        redisTemplate.opsForHash().put(key, "last_update", 
            String.valueOf(System.currentTimeMillis()));
        
        // TTL of 30 days for old files
        redisTemplate.expire(key, 30, TimeUnit.DAYS);
    }
}
```

### Step 8: REST Controller

File: `src/main/java/it/berlink/monitoring/controller/QueryMonitorController.java`

```java
package it.berlink.monitoring.controller;

import it.berlink.monitoring.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/query-monitor")
@CrossOrigin
@Slf4j
public class QueryMonitorController {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Value("${query.monitor.retention.days:15}")
    private int retentionDays;
    
    private static final String QUERY_DETAILS_PREFIX = "query:details:";
    private static final String QUERY_EXECUTIONS_PREFIX = "query:executions:";
    private static final String SLOW_QUERIES_SORTED = "query:slow:sorted";
    private static final String QUERY_STATS_PREFIX = "query:stats:";
    private static final String PROCESSOR_STATE_KEY = "query:processor:state";
    
    @GetMapping("/slowest")
    public List<QueryMetric> getSlowestQueries(
            @RequestParam(defaultValue = "20") int limit) {
        
        Set<ZSetOperations.TypedTuple<String>> slowest = 
            redisTemplate.opsForZSet()
                .reverseRangeWithScores(SLOW_QUERIES_SORTED, 0, limit - 1);
        
        if (slowest == null) return List.of();
        
        return slowest.stream()
            .map(tuple -> getQueryStats(tuple.getValue()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    @GetMapping("/most-frequent")
    public List<QueryMetric> getMostFrequentQueries(
            @RequestParam(defaultValue = "20") int limit) {
        
        Set<String> keys = redisTemplate.keys(QUERY_STATS_PREFIX + "*");
        if (keys == null) return List.of();
        
        return keys.stream()
            .map(key -> key.replace(QUERY_STATS_PREFIX, ""))
            .map(this::getQueryStats)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(QueryMetric::getCount).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @GetMapping("/{hash}")
    public QueryDetail getQueryDetail(@PathVariable String hash) {
        String query = redisTemplate.opsForValue().get(QUERY_DETAILS_PREFIX + hash);
        if (query == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Query not found");
        }
        
        Map<Object, Object> stats = redisTemplate.opsForHash()
            .entries(QUERY_STATS_PREFIX + hash);
        
        // Get last 100 execution times
        Set<ZSetOperations.TypedTuple<String>> executions = 
            redisTemplate.opsForZSet()
                .reverseRangeWithScores(QUERY_EXECUTIONS_PREFIX + hash, 0, 99);
        
        List<ExecutionPoint> executionHistory = executions != null ? 
            executions.stream()
                .map(e -> new ExecutionPoint(
                    e.getScore().longValue(),
                    Integer.parseInt(e.getValue())
                ))
                .collect(Collectors.toList()) : List.of();
        
        return QueryDetail.builder()
            .hash(hash)
            .query(query)
            .count(getLong(stats, "count"))
            .avgTime(getAvg(stats))
            .minTime(getLong(stats, "min"))
            .maxTime(getLong(stats, "max"))
            .p95Time(calculateP95(hash))
            .lastSeen(getLong(stats, "last_seen"))
            .executionHistory(executionHistory)
            .build();
    }
    
    @GetMapping("/overview")
    public MonitorOverview getOverview() {
        Set<String> allQueryKeys = redisTemplate.keys(QUERY_STATS_PREFIX + "*");
        int totalQueries = allQueryKeys != null ? allQueryKeys.size() : 0;
        
        long totalExecutions = 0;
        long totalTime = 0;
        
        if (allQueryKeys != null) {
            for (String key : allQueryKeys) {
                Map<Object, Object> stats = redisTemplate.opsForHash().entries(key);
                totalExecutions += getLong(stats, "count");
                totalTime += getLong(stats, "total_time");
            }
        }
        
        return MonitorOverview.builder()
            .uniqueQueries(totalQueries)
            .totalExecutions(totalExecutions)
            .avgExecutionTime(totalExecutions > 0 ? totalTime / totalExecutions : 0)
            .retentionDays(retentionDays)
            .build();
    }
    
    @GetMapping("/processor/status")
    public ProcessorStatus getProcessorStatus() {
        Map<Object, Object> state = redisTemplate.opsForHash()
            .entries(PROCESSOR_STATE_KEY);
        
        long lastUpdate = state.containsKey("last_update") ? 
            Long.parseLong(state.get("last_update").toString()) : 0;
        
        boolean isHealthy = (System.currentTimeMillis() - lastUpdate) < 60000; // < 1 min
        
        return ProcessorStatus.builder()
            .healthy(isHealthy)
            .lastProcessedFile((String) state.get("filename"))
            .lastProcessedPosition(state.containsKey("position") ? 
                Long.parseLong(state.get("position").toString()) : 0)
            .lastUpdateTime(lastUpdate)
            .secondsSinceLastUpdate((System.currentTimeMillis() - lastUpdate) / 1000)
            .build();
    }
    
    // Helper methods
    private QueryMetric getQueryStats(String hash) {
        String query = redisTemplate.opsForValue().get(QUERY_DETAILS_PREFIX + hash);
        if (query == null) return null;
        
        Map<Object, Object> stats = redisTemplate.opsForHash()
            .entries(QUERY_STATS_PREFIX + hash);
        
        return QueryMetric.builder()
            .hash(hash)
            .query(truncate(query, 100))
            .count(getLong(stats, "count"))
            .avgTime(getAvg(stats))
            .minTime(getLong(stats, "min"))
            .maxTime(getLong(stats, "max"))
            .p95Time(calculateP95(hash))
            .lastSeen(getLong(stats, "last_seen"))
            .build();
    }
    
    private long getLong(Map<Object, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? Long.parseLong(val.toString()) : 0L;
    }
    
    private long getAvg(Map<Object, Object> stats) {
        long count = getLong(stats, "count");
        long total = getLong(stats, "total_time");
        return count > 0 ? total / count : 0;
    }
    
    private long calculateP95(String hash) {
        String executionsKey = QUERY_EXECUTIONS_PREFIX + hash;
        Long count = redisTemplate.opsForZSet().size(executionsKey);
        
        if (count == null || count == 0) return 0;
        
        long p95Index = (long) Math.ceil(count * 0.95) - 1;
        Set<String> p95Set = redisTemplate.opsForZSet()
            .reverseRange(executionsKey, p95Index, p95Index);
        
        return p95Set != null && !p95Set.isEmpty() ? 
            Long.parseLong(p95Set.iterator().next()) : 0;
    }
    
    private String truncate(String str, int maxLen) {
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
```

### Step 9: Enable Scheduling

File: `src/main/java/it/berlink/Application.java`

```java
package com.yourapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Add this annotation
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## Log File Format

The system supports multiple log formats. Configure your application to log queries in one of these formats:

### Format 1 (Recommended - Pipe-delimited)
```
2026-01-25 16:22:36.600 | SELECT * FROM users WHERE id = ? | 145ms
2026-01-25 16:22:37.123 | UPDATE orders SET status = ? WHERE id = ? | 89ms
```

### Format 2 (Key-value pairs)
```
[2026-01-25 16:22:36] duration=145ms query=SELECT * FROM users WHERE id = ?
[2026-01-25 16:22:37] duration=89ms query=UPDATE orders SET status = ? WHERE id = ?
```

### Format 3 (Labeled format)
```
2026-01-25 16:22:36 INFO Query: SELECT * FROM users WHERE id = ? | Time: 145ms
2026-01-25 16:22:37 INFO Query: UPDATE orders SET status = ? | Time: 89ms
```

### Format 4 (JSON)
```json
{"timestamp":"2026-01-25 16:22:36","query":"SELECT * FROM users WHERE id = ?","duration":145}
{"timestamp":"2026-01-25 16:22:37","query":"UPDATE orders SET status = ?","duration":89}
```

## API Endpoints

### GET /api/query-monitor/slowest?limit=20
Returns the slowest queries ranked by P95 execution time.

**Response:**
```json
[
  {
    "hash": "a1b2c3d4e5f6g7h8",
    "query": "SELECT * FROM large_table WHERE complex_condition = ?",
    "count": 1523,
    "avgTime": 234,
    "minTime": 45,
    "maxTime": 1234,
    "p95Time": 567,
    "lastSeen": 1706198556600
  }
]
```

### GET /api/query-monitor/most-frequent?limit=20
Returns queries with the highest execution count.

### GET /api/query-monitor/{hash}
Returns detailed information about a specific query including execution history.

**Response:**
```json
{
  "hash": "a1b2c3d4e5f6g7h8",
  "query": "SELECT * FROM users WHERE id = ?",
  "count": 1523,
  "avgTime": 234,
  "minTime": 45,
  "maxTime": 1234,
  "p95Time": 567,
  "lastSeen": 1706198556600,
  "executionHistory": [
    {"timestamp": 1706198556600, "duration": 145},
    {"timestamp": 1706198556500, "duration": 132}
  ]
}
```

### GET /api/query-monitor/overview
Returns overall system statistics.

**Response:**
```json
{
  "uniqueQueries": 234,
  "totalExecutions": 45678,
  "avgExecutionTime": 123,
  "retentionDays": 15
}
```

### GET /api/query-monitor/processor/status
Returns the status of the log file processor.

**Response:**
```json
{
  "healthy": true,
  "lastProcessedFile": "/var/log/app/query.log",
  "lastProcessedPosition": 1234567,
  "lastUpdateTime": 1706198556600,
  "secondsSinceLastUpdate": 3
}
```

## Testing

### 1. Generate Test Log File

```bash
# Create test log directory
mkdir -p /var/log/app

# Generate test data
cat > /var/log/app/query.log << 'EOF'
2026-01-25 16:22:36.600 | SELECT * FROM users WHERE id = ? | 145ms
2026-01-25 16:22:37.123 | SELECT * FROM orders WHERE user_id = ? | 234ms
2026-01-25 16:22:37.456 | UPDATE users SET last_login = ? WHERE id = ? | 89ms
2026-01-25 16:22:38.789 | SELECT * FROM products WHERE category = ? | 567ms
2026-01-25 16:22:39.012 | DELETE FROM cache WHERE expires_at < ? | 23ms
EOF
```

### 2. Verify Valkey is Running

```bash
# Using redis-cli (works with Valkey too)
redis-cli ping
# Should return: PONG

# Check keys
redis-cli keys "query:*"
```

### 3. Start Application

```bash
mvn spring-boot:run
```

### 4. Check Processor Status

```bash
curl http://localhost:8080/api/query-monitor/processor/status
```

### 5. View Slowest Queries

```bash
curl http://localhost:8080/api/query-monitor/slowest
```

### 6. View Overview

```bash
curl http://localhost:8080/api/query-monitor/overview
```

## Monitoring and Maintenance

### Check Valkey Memory Usage

```bash
redis-cli info memory
```

### View All Query Keys

```bash
redis-cli keys "query:*" | head -20
```

### Manually Inspect Query Data

```bash
# Get query details
redis-cli GET "query:details:a1b2c3d4e5f6g7h8"

# Get query stats
redis-cli HGETALL "query:stats:a1b2c3d4e5f6g7h8"

# Get execution count
redis-cli ZCARD "query:executions:a1b2c3d4e5f6g7h8"

# Get slowest queries
redis-cli ZREVRANGE "query:slow:sorted" 0 9 WITHSCORES
```

### Manual Cleanup (if needed)

```bash
# Delete all query monitoring data
redis-cli KEYS "query:*" | xargs redis-cli DEL

# Delete only old execution data
redis-cli EVAL "local keys = redis.call('keys', 'query:executions:*'); for i=1,#keys do redis.call('del', keys[i]) end; return #keys" 0
```

## Troubleshooting

### Processor Not Reading New Logs

1. Check file permissions:
```bash
ls -la /var/log/app/query.log
```

2. Verify log format matches parser patterns

3. Check processor status endpoint for errors

4. Check application logs:
```bash
tail -f logs/application.log | grep QueryLogFileProcessor
```

### No Data in Valkey

1. Verify Valkey is running and accessible
2. Check Redis configuration in application.yml
3. Verify log file path is correct
4. Check if any queries were actually logged

### High Memory Usage in Valkey

1. Reduce retention period in configuration
2. Increase cleanup frequency
3. Limit number of executions stored per query

## Performance Optimization

### Tune Processor Interval

```yaml
query:
  log:
    processor:
      interval: 10000  # Increase to 10 seconds for less frequent processing
```

### Reduce Data Granularity

Modify `QueryMonitorService.logQuery()` to sample:

```java
// Only log 10% of queries
if (Math.random() < 0.1) {
    monitorService.logQuery(query, duration);
}
```

### Batch Processing

Increase batch size for file reading:

```java
// Read larger chunks at once
BufferedReader reader = new BufferedReader(new FileReader(logFile), 65536);
```

## Future Enhancements

- [ ] Add alerting for slow queries (email/Slack)
- [ ] Add query trend analysis (performance degradation detection)
- [ ] Add export to CSV/Excel
- [ ] Add query execution plan capture
- [ ] Add user/session tracking
- [ ] Add geographic distribution analysis
- [ ] Add PostgreSQL fallback for long-term storage
- [ ] Add Grafana dashboard integration
