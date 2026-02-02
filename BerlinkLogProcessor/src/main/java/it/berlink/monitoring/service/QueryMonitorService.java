package it.berlink.monitoring.service;

import it.berlink.monitoring.model.DurationDistribution;
import it.berlink.monitoring.model.ExecutionPoint;
import it.berlink.monitoring.model.MonitorOverview;
import it.berlink.monitoring.model.QueryDetail;
import it.berlink.monitoring.model.QueryMetric;
import it.berlink.monitoring.model.TimeSeriesPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for storing and retrieving query metrics from Redis/Valkey.
 */
@Slf4j
@Service
public class QueryMonitorService {

    private static final String KEY_PREFIX = "query:";
    private static final String METRIC_SUFFIX = ":metric";
    private static final String SAMPLES_SUFFIX = ":samples";
    private static final String INDEX_KEY = "query:index";
    private static final String OVERVIEW_KEY = "query:overview";

    private final RedisTemplate<String, Object> redisTemplate;
    private final int ttlDays;
    private final int maxSamples;

    public QueryMonitorService(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${query.monitor.ttl.days:15}") int ttlDays,
            @Value("${query.monitor.max.samples:1000}") int maxSamples) {
        this.redisTemplate = redisTemplate;
        this.ttlDays = ttlDays;
        this.maxSamples = maxSamples;
    }

    /**
     * Records a query execution.
     */
    public void recordExecution(String queryHash, String normalizedQuery, ExecutionPoint execution) {
        String metricKey = KEY_PREFIX + queryHash + METRIC_SUFFIX;
        String samplesKey = KEY_PREFIX + queryHash + SAMPLES_SUFFIX;

        // Add to samples list (LPUSH + LTRIM for bounded list)
        redisTemplate.opsForList().leftPush(samplesKey, execution);
        redisTemplate.opsForList().trim(samplesKey, 0, maxSamples - 1);
        redisTemplate.expire(samplesKey, Duration.ofDays(ttlDays));

        // Update metrics
        updateMetrics(queryHash, normalizedQuery, metricKey, samplesKey);

        // Add to index
        redisTemplate.opsForSet().add(INDEX_KEY, queryHash);
        redisTemplate.expire(INDEX_KEY, Duration.ofDays(ttlDays));

        log.debug("Recorded execution for query {}: {}ms", queryHash, execution.getDurationMs());
    }

    private void updateMetrics(String queryHash, String normalizedQuery, String metricKey, String samplesKey) {
        List<Object> rawSamples = redisTemplate.opsForList().range(samplesKey, 0, -1);
        if (rawSamples == null || rawSamples.isEmpty()) {
            return;
        }

        List<ExecutionPoint> samples = rawSamples.stream()
            .filter(obj -> obj instanceof ExecutionPoint)
            .map(obj -> (ExecutionPoint) obj)
            .filter(ep -> ep.getTimestamp() != null)
            .toList();

        if (samples.isEmpty()) {
            // Try to handle LinkedHashMap format from JSON deserialization
            samples = rawSamples.stream()
                .filter(obj -> obj instanceof Map)
                .map(this::mapToExecutionPoint)
                .filter(Objects::nonNull)
                .toList();
        }

        if (samples.isEmpty()) {
            return;
        }

        long[] durations = samples.stream()
            .mapToLong(ExecutionPoint::getDurationMs)
            .sorted()
            .toArray();

        // Compute the most frequent method from samples
        String topMethod = samples.stream()
            .map(ExecutionPoint::getMethod)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(m -> m, Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        QueryMetric metric = QueryMetric.builder()
            .queryHash(queryHash)
            .queryPattern(normalizedQuery)
            .executionCount(durations.length)
            .avgDurationMs(Arrays.stream(durations).average().orElse(0))
            .minDurationMs(durations[0])
            .maxDurationMs(durations[durations.length - 1])
            .p50DurationMs(percentile(durations, 50))
            .p95DurationMs(percentile(durations, 95))
            .p99DurationMs(percentile(durations, 99))
            .firstSeen(samples.stream()
                .map(ExecutionPoint::getTimestamp)
                .min(Instant::compareTo)
                .orElse(Instant.now()))
            .lastSeen(samples.stream()
                .map(ExecutionPoint::getTimestamp)
                .max(Instant::compareTo)
                .orElse(Instant.now()))
            .topMethod(topMethod)
            .build();

        redisTemplate.opsForValue().set(metricKey, metric, Duration.ofDays(ttlDays));
    }

    @SuppressWarnings("unchecked")
    private ExecutionPoint mapToExecutionPoint(Object obj) {
        try {
            Map<String, Object> map = (Map<String, Object>) obj;
            Instant timestamp = null;
            Object tsObj = map.get("timestamp");
            if (tsObj instanceof String) {
                timestamp = Instant.parse((String) tsObj);
            } else if (tsObj instanceof Number) {
                timestamp = Instant.ofEpochMilli(((Number) tsObj).longValue());
            }

            long durationMs = ((Number) map.get("durationMs")).longValue();
            int rowCount = map.get("rowCount") != null ? ((Number) map.get("rowCount")).intValue() : 0;
            String method = map.get("method") != null ? (String) map.get("method") : null;

            return ExecutionPoint.builder()
                .timestamp(timestamp)
                .durationMs(durationMs)
                .rowCount(rowCount)
                .method(method)
                .build();
        } catch (Exception e) {
            log.warn("Failed to convert map to ExecutionPoint: {}", e.getMessage());
            return null;
        }
    }

    private long percentile(long[] sortedValues, int percentile) {
        if (sortedValues.length == 0) return 0;
        if (sortedValues.length == 1) return sortedValues[0];

        double index = (percentile / 100.0) * (sortedValues.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sortedValues[lower];
        }

        double fraction = index - lower;
        return (long) (sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower]));
    }

    /**
     * Returns the slowest queries by P95 duration.
     */
    public List<QueryMetric> getSlowestQueries(int limit) {
        return getAllMetrics().stream()
            .sorted(Comparator.comparingLong(QueryMetric::getP95DurationMs).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Returns the most frequently executed queries.
     */
    public List<QueryMetric> getMostFrequentQueries(int limit) {
        return getAllMetrics().stream()
            .sorted(Comparator.comparingLong(QueryMetric::getExecutionCount).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Returns detailed information about a specific query.
     */
    public Optional<QueryDetail> getQueryDetail(String queryHash) {
        String metricKey = KEY_PREFIX + queryHash + METRIC_SUFFIX;
        String samplesKey = KEY_PREFIX + queryHash + SAMPLES_SUFFIX;

        Object metricObj = redisTemplate.opsForValue().get(metricKey);
        if (metricObj == null) {
            return Optional.empty();
        }

        QueryMetric metric = convertToQueryMetric(metricObj);
        if (metric == null) {
            return Optional.empty();
        }

        List<Object> rawSamples = redisTemplate.opsForList().range(samplesKey, 0, 99);
        List<ExecutionPoint> recentExecutions = new ArrayList<>();

        if (rawSamples != null) {
            for (Object obj : rawSamples) {
                if (obj instanceof ExecutionPoint ep) {
                    recentExecutions.add(ep);
                } else if (obj instanceof Map) {
                    ExecutionPoint ep = mapToExecutionPoint(obj);
                    if (ep != null) {
                        recentExecutions.add(ep);
                    }
                }
            }
        }

        return Optional.of(QueryDetail.builder()
            .metrics(metric)
            .recentExecutions(recentExecutions)
            .build());
    }

    /**
     * Returns overview statistics.
     */
    public MonitorOverview getOverview() {
        List<QueryMetric> allMetrics = getAllMetrics();

        if (allMetrics.isEmpty()) {
            return MonitorOverview.builder()
                .totalQueriesTracked(0)
                .totalExecutions(0)
                .avgDurationMs(0)
                .slowestQueryP95Ms(0)
                .slowestQueryHash(null)
                .monitoringStartTime(Instant.now())
                .lastUpdateTime(Instant.now())
                .build();
        }

        long totalExecutions = allMetrics.stream()
            .mapToLong(QueryMetric::getExecutionCount)
            .sum();

        double avgDuration = allMetrics.stream()
            .mapToDouble(m -> m.getAvgDurationMs() * m.getExecutionCount())
            .sum() / totalExecutions;

        QueryMetric slowest = allMetrics.stream()
            .max(Comparator.comparingLong(QueryMetric::getP95DurationMs))
            .orElse(null);

        Instant firstSeen = allMetrics.stream()
            .map(QueryMetric::getFirstSeen)
            .filter(Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(Instant.now());

        Instant lastSeen = allMetrics.stream()
            .map(QueryMetric::getLastSeen)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(Instant.now());

        return MonitorOverview.builder()
            .totalQueriesTracked(allMetrics.size())
            .totalExecutions(totalExecutions)
            .avgDurationMs(avgDuration)
            .slowestQueryP95Ms(slowest != null ? slowest.getP95DurationMs() : 0)
            .slowestQueryHash(slowest != null ? slowest.getQueryHash() : null)
            .monitoringStartTime(firstSeen)
            .lastUpdateTime(lastSeen)
            .build();
    }

    private List<QueryMetric> getAllMetrics() {
        Set<Object> hashes = redisTemplate.opsForSet().members(INDEX_KEY);
        if (hashes == null || hashes.isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryMetric> metrics = new ArrayList<>();
        for (Object hashObj : hashes) {
            String hash = hashObj.toString();
            String metricKey = KEY_PREFIX + hash + METRIC_SUFFIX;
            Object metricObj = redisTemplate.opsForValue().get(metricKey);
            if (metricObj != null) {
                QueryMetric metric = convertToQueryMetric(metricObj);
                if (metric != null) {
                    metrics.add(metric);
                }
            }
        }

        return metrics;
    }

    @SuppressWarnings("unchecked")
    private QueryMetric convertToQueryMetric(Object obj) {
        if (obj instanceof QueryMetric qm) {
            return qm;
        }

        if (obj instanceof Map) {
            try {
                Map<String, Object> map = (Map<String, Object>) obj;
                return QueryMetric.builder()
                    .queryHash((String) map.get("queryHash"))
                    .queryPattern((String) map.get("queryPattern"))
                    .executionCount(((Number) map.get("executionCount")).longValue())
                    .avgDurationMs(((Number) map.get("avgDurationMs")).doubleValue())
                    .minDurationMs(((Number) map.get("minDurationMs")).longValue())
                    .maxDurationMs(((Number) map.get("maxDurationMs")).longValue())
                    .p50DurationMs(((Number) map.get("p50DurationMs")).longValue())
                    .p95DurationMs(((Number) map.get("p95DurationMs")).longValue())
                    .p99DurationMs(((Number) map.get("p99DurationMs")).longValue())
                    .firstSeen(parseInstant(map.get("firstSeen")))
                    .lastSeen(parseInstant(map.get("lastSeen")))
                    .topMethod((String) map.get("topMethod"))
                    .build();
            } catch (Exception e) {
                log.warn("Failed to convert map to QueryMetric: {}", e.getMessage());
                return null;
            }
        }

        return null;
    }

    private Instant parseInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String str) {
            return Instant.parse(str);
        }
        if (obj instanceof Number num) {
            return Instant.ofEpochMilli(num.longValue());
        }
        return null;
    }

    /**
     * Returns time series data aggregated by hourly intervals.
     *
     * @param hours Number of hours to look back
     * @return List of time series points with aggregated metrics
     */
    public List<TimeSeriesPoint> getTimeSeriesData(int hours) {
        List<ExecutionPoint> allExecutions = getAllExecutions();
        if (allExecutions.isEmpty()) {
            return Collections.emptyList();
        }

        Instant cutoff = Instant.now().minus(Duration.ofHours(hours));

        // Filter executions within the time range
        List<ExecutionPoint> filteredExecutions = allExecutions.stream()
            .filter(ep -> ep.getTimestamp() != null && ep.getTimestamp().isAfter(cutoff))
            .toList();

        if (filteredExecutions.isEmpty()) {
            return Collections.emptyList();
        }

        // Group by hour
        Map<Instant, List<ExecutionPoint>> byHour = filteredExecutions.stream()
            .collect(Collectors.groupingBy(ep -> {
                Instant ts = ep.getTimestamp();
                return ts.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            }));

        // Convert to time series points
        List<TimeSeriesPoint> result = new ArrayList<>();
        for (Map.Entry<Instant, List<ExecutionPoint>> entry : byHour.entrySet()) {
            List<ExecutionPoint> hourExecutions = entry.getValue();

            long[] durations = hourExecutions.stream()
                .mapToLong(ExecutionPoint::getDurationMs)
                .sorted()
                .toArray();

            double avg = Arrays.stream(durations).average().orElse(0);
            long p95 = percentile(durations, 95);

            result.add(TimeSeriesPoint.builder()
                .timestamp(entry.getKey())
                .avgDurationMs(avg)
                .p95DurationMs(p95)
                .executionCount(hourExecutions.size())
                .build());
        }

        // Sort by timestamp
        result.sort(Comparator.comparing(TimeSeriesPoint::getTimestamp));
        return result;
    }

    /**
     * Returns the distribution of execution durations across predefined buckets.
     *
     * @return Duration distribution with counts per bucket
     */
    public DurationDistribution getDurationDistribution() {
        List<ExecutionPoint> allExecutions = getAllExecutions();

        long under10 = 0, from10to50 = 0, from50to100 = 0, from100to500 = 0, over500 = 0;

        for (ExecutionPoint ep : allExecutions) {
            long duration = ep.getDurationMs();
            if (duration < 10) {
                under10++;
            } else if (duration < 50) {
                from10to50++;
            } else if (duration < 100) {
                from50to100++;
            } else if (duration < 500) {
                from100to500++;
            } else {
                over500++;
            }
        }

        return DurationDistribution.builder()
            .under10ms(under10)
            .from10to50ms(from10to50)
            .from50to100ms(from50to100)
            .from100to500ms(from100to500)
            .over500ms(over500)
            .totalExecutions(allExecutions.size())
            .build();
    }

    /**
     * Retrieves all execution points from all tracked queries.
     */
    private List<ExecutionPoint> getAllExecutions() {
        Set<Object> hashes = redisTemplate.opsForSet().members(INDEX_KEY);
        if (hashes == null || hashes.isEmpty()) {
            return Collections.emptyList();
        }

        List<ExecutionPoint> allExecutions = new ArrayList<>();
        for (Object hashObj : hashes) {
            String hash = hashObj.toString();
            String samplesKey = KEY_PREFIX + hash + SAMPLES_SUFFIX;
            List<Object> rawSamples = redisTemplate.opsForList().range(samplesKey, 0, -1);

            if (rawSamples != null) {
                for (Object obj : rawSamples) {
                    if (obj instanceof ExecutionPoint ep) {
                        if (ep.getTimestamp() != null) {
                            allExecutions.add(ep);
                        }
                    } else if (obj instanceof Map) {
                        ExecutionPoint ep = mapToExecutionPoint(obj);
                        if (ep != null && ep.getTimestamp() != null) {
                            allExecutions.add(ep);
                        }
                    }
                }
            }
        }

        return allExecutions;
    }
}
