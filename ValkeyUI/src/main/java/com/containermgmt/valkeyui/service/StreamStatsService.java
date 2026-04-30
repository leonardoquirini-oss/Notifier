package com.containermgmt.valkeyui.service;

import com.containermgmt.valkeyui.config.ValkeyUiProperties;
import com.containermgmt.valkeyui.model.StreamMessage;
import com.containermgmt.valkeyui.model.TimeBucket;
import com.containermgmt.valkeyui.model.TimeSeriesPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamStatsService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ValkeyUiProperties properties;

    public List<TimeBucket> messagesPerBucket(String key, String bucket, String range) {
        long now = System.currentTimeMillis();
        long rangeMs = parseRangeMs(range);
        long bucketMs = parseBucketMs(bucket);
        long fromMs = now - rangeMs;

        TreeMap<Long, Long> buckets = initBuckets(fromMs, now, bucketMs);
        countMessages(key, fromMs, now, bucketMs, buckets);
        return toList(buckets);
    }

    public long[][] heatmapWeekly(String key) {
        long now = System.currentTimeMillis();
        long fromMs = now - 7L * 24L * 3600L * 1000L;
        long[][] grid = new long[7][24];

        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Range<String> range = Range.of(
                Range.Bound.inclusive(fromMs + "-0"),
                Range.Bound.inclusive(now + "-" + Long.MAX_VALUE));
        long maxCall = properties.getQuery().getMaxMessagesPerCall();
        org.springframework.data.redis.connection.Limit limit =
                org.springframework.data.redis.connection.Limit.limit().count((int) Math.min(maxCall, Integer.MAX_VALUE));

        List<MapRecord<String, Object, Object>> records;
        try {
            records = ops.range(key, range, limit);
        } catch (Exception e) {
            log.warn("Heatmap range failed for {}: {}", key, e.getMessage());
            return grid;
        }
        if (records == null) return grid;

        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        for (MapRecord<String, Object, Object> r : records) {
            RecordId rid = r.getId();
            if (rid == null) continue;
            long ts = StreamIdUtils.timestampMs(rid.getValue());
            java.time.ZonedDateTime zdt = Instant.ofEpochMilli(ts).atZone(utc);
            int dow = (zdt.getDayOfWeek().getValue() - 1) % 7;
            int hour = zdt.getHour();
            grid[dow][hour]++;
        }
        return grid;
    }

    public List<TimeSeriesPoint> timeseries(String streamKey, String metric, String range) {
        String metricsKey = properties.getSampler().getMetricsStreamPrefix() + streamKey;
        long now = System.currentTimeMillis();
        long rangeMs = parseRangeMs(range);
        long fromMs = now - rangeMs;

        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Range<String> r = Range.of(
                Range.Bound.inclusive(fromMs + "-0"),
                Range.Bound.inclusive(now + "-" + Long.MAX_VALUE));
        long maxCall = properties.getQuery().getMaxMessagesPerCall();
        org.springframework.data.redis.connection.Limit limit =
                org.springframework.data.redis.connection.Limit.limit().count((int) Math.min(maxCall, Integer.MAX_VALUE));

        List<MapRecord<String, Object, Object>> records;
        try {
            records = ops.range(metricsKey, r, limit);
        } catch (Exception e) {
            log.debug("Timeseries range failed for {}: {}", metricsKey, e.getMessage());
            return List.of();
        }
        if (records == null) return List.of();

        String field = "memory".equalsIgnoreCase(metric) ? "memory_bytes"
                : "pending".equalsIgnoreCase(metric) ? "pending_total"
                : "length";

        List<TimeSeriesPoint> out = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> rec : records) {
            RecordId rid = rec.getId();
            if (rid == null) continue;
            long ts = StreamIdUtils.timestampMs(rid.getValue());
            Object raw = rec.getValue().get(field);
            Long value = parseLong(raw);
            out.add(TimeSeriesPoint.builder()
                    .timestampMs(ts)
                    .value(value)
                    .build());
        }
        return out;
    }

    private TreeMap<Long, Long> initBuckets(long fromMs, long toMs, long bucketMs) {
        TreeMap<Long, Long> buckets = new TreeMap<>();
        long start = (fromMs / bucketMs) * bucketMs;
        for (long t = start; t <= toMs; t += bucketMs) {
            buckets.put(t, 0L);
        }
        return buckets;
    }

    private void countMessages(String key, long fromMs, long toMs, long bucketMs,
                               TreeMap<Long, Long> buckets) {
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Range<String> range = Range.of(
                Range.Bound.inclusive(fromMs + "-0"),
                Range.Bound.inclusive(toMs + "-" + Long.MAX_VALUE));
        long maxCall = properties.getQuery().getMaxMessagesPerCall();
        org.springframework.data.redis.connection.Limit limit =
                org.springframework.data.redis.connection.Limit.limit().count((int) Math.min(maxCall, Integer.MAX_VALUE));

        List<MapRecord<String, Object, Object>> records;
        try {
            records = ops.range(key, range, limit);
        } catch (Exception e) {
            log.warn("Stats range failed for {}: {}", key, e.getMessage());
            return;
        }
        if (records == null) return;
        for (MapRecord<String, Object, Object> r : records) {
            RecordId rid = r.getId();
            if (rid == null) continue;
            long ts = StreamIdUtils.timestampMs(rid.getValue());
            long bucket = (ts / bucketMs) * bucketMs;
            buckets.merge(bucket, 1L, Long::sum);
        }
    }

    private List<TimeBucket> toList(Map<Long, Long> buckets) {
        List<TimeBucket> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, Long> e : buckets.entrySet()) {
            out.add(TimeBucket.builder()
                    .timestampMs(e.getKey())
                    .count(e.getValue())
                    .build());
        }
        return out;
    }

    private long parseRangeMs(String range) {
        if (range == null) return 24L * 3600_000L;
        return switch (range.toLowerCase()) {
            case "7d" -> 7L * 24L * 3600_000L;
            case "30d" -> 30L * 24L * 3600_000L;
            default -> 24L * 3600_000L;
        };
    }

    private long parseBucketMs(String bucket) {
        if (bucket == null) return 60_000L;
        return switch (bucket.toLowerCase()) {
            case "hour" -> 3600_000L;
            case "day" -> 24L * 3600_000L;
            default -> 60_000L;
        };
    }

    private Long parseLong(Object v) {
        if (v == null) return null;
        try {
            String s = v instanceof byte[] ? new String((byte[]) v, java.nio.charset.StandardCharsets.UTF_8) : v.toString();
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

}
