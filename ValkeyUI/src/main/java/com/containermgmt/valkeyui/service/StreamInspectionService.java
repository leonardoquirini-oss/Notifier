package com.containermgmt.valkeyui.service;

import com.containermgmt.valkeyui.model.ConsumerGroupInfo;
import com.containermgmt.valkeyui.model.ConsumerInfo;
import com.containermgmt.valkeyui.model.StreamMessage;
import com.containermgmt.valkeyui.model.StreamSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamInspectionService {

    private final RedisTemplate<String, String> redisTemplate;

    public long length(String key) {
        Long len = redisTemplate.opsForStream().size(key);
        return len == null ? 0L : len;
    }

    public Long memoryUsage(String key) {
        return redisTemplate.execute((RedisCallback<Long>) connection -> {
            try {
                Object reply = connection.execute("MEMORY",
                        "USAGE".getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8));
                if (reply == null) return null;
                if (reply instanceof Long) return (Long) reply;
                if (reply instanceof Number) return ((Number) reply).longValue();
                return Long.parseLong(reply.toString());
            } catch (Exception e) {
                log.debug("MEMORY USAGE failed for {}: {}", key, e.getMessage());
                return null;
            }
        });
    }

    public StreamSummary summary(String key) {
        long length = length(key);
        Map<String, Object> info = xinfoStream(key);
        List<ConsumerGroupInfo> groups = consumerGroups(key);
        long pending = groups.stream().mapToLong(ConsumerGroupInfo::getPending).sum();
        Long memory = memoryUsage(key);

        String firstId = (String) info.getOrDefault("first-entry-id", null);
        String lastId = (String) info.getOrDefault("last-entry-id", null);
        Long firstTs = firstId != null ? StreamIdUtils.timestampMs(firstId) : null;
        Long lastTs = lastId != null ? StreamIdUtils.timestampMs(lastId) : null;

        return StreamSummary.builder()
                .key(key)
                .length(length)
                .firstId(firstId)
                .lastId(lastId)
                .firstTimestampMs(firstTs)
                .lastTimestampMs(lastTs)
                .groupCount(groups.size())
                .pendingTotal(pending)
                .memoryBytes(memory)
                .build();
    }

    public Map<String, Object> xinfoStream(String key) {
        return redisTemplate.execute((RedisCallback<Map<String, Object>>) connection -> {
            try {
                Object reply = connection.execute("XINFO",
                        "STREAM".getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8));
                Map<String, Object> map = parseFlatMap(reply);
                Object first = map.get("first-entry");
                Object last = map.get("last-entry");
                if (first instanceof List<?> list && !list.isEmpty()) {
                    map.put("first-entry-id", toStringValue(list.get(0)));
                }
                if (last instanceof List<?> list && !list.isEmpty()) {
                    map.put("last-entry-id", toStringValue(list.get(0)));
                }
                return map;
            } catch (Exception e) {
                log.debug("XINFO STREAM failed for {}: {}", key, e.getMessage());
                return Collections.emptyMap();
            }
        });
    }

    public List<ConsumerGroupInfo> consumerGroups(String key) {
        List<Map<String, Object>> raw = xinfoGroups(key);
        List<ConsumerGroupInfo> result = new ArrayList<>();
        for (Map<String, Object> g : raw) {
            String name = toStringValue(g.get("name"));
            long pending = toLong(g.get("pending"), 0L);
            String lastId = toStringValue(g.get("last-delivered-id"));
            long lag = toLong(g.get("lag"), 0L);
            List<ConsumerInfo> consumers = consumers(key, name);
            result.add(ConsumerGroupInfo.builder()
                    .name(name)
                    .pending(pending)
                    .lastDeliveredId(lastId)
                    .lag(lag)
                    .consumers(consumers)
                    .build());
        }
        return result;
    }

    private List<Map<String, Object>> xinfoGroups(String key) {
        return redisTemplate.execute((RedisCallback<List<Map<String, Object>>>) connection -> {
            try {
                Object reply = connection.execute("XINFO",
                        "GROUPS".getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8));
                return parseListOfMaps(reply);
            } catch (Exception e) {
                log.debug("XINFO GROUPS failed for {}: {}", key, e.getMessage());
                return List.of();
            }
        });
    }

    public List<ConsumerInfo> consumers(String key, String group) {
        return redisTemplate.execute((RedisCallback<List<ConsumerInfo>>) connection -> {
            try {
                Object reply = connection.execute("XINFO",
                        "CONSUMERS".getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8),
                        group.getBytes(StandardCharsets.UTF_8));
                List<Map<String, Object>> raw = parseListOfMaps(reply);
                List<ConsumerInfo> out = new ArrayList<>();
                for (Map<String, Object> c : raw) {
                    out.add(ConsumerInfo.builder()
                            .name(toStringValue(c.get("name")))
                            .pending(toLong(c.get("pending"), 0L))
                            .idleMs(toLong(c.get("idle"), 0L))
                            .build());
                }
                return out;
            } catch (Exception e) {
                log.debug("XINFO CONSUMERS failed for {}/{}: {}", key, group, e.getMessage());
                return List.of();
            }
        });
    }

    public List<StreamMessage> readRange(String key, String from, String to, long limit, boolean reverse) {
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Range<String> range = buildRange(from, to);
        org.springframework.data.redis.connection.Limit l = org.springframework.data.redis.connection.Limit.limit().count((int) Math.min(limit, Integer.MAX_VALUE));
        List<MapRecord<String, Object, Object>> records = reverse
                ? ops.reverseRange(key, range, l)
                : ops.range(key, range, l);
        if (records == null) {
            return List.of();
        }
        List<StreamMessage> out = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> r : records) {
            RecordId rid = r.getId();
            String id = rid == null ? "" : rid.getValue();
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : r.getValue().entrySet()) {
                fields.put(toStringValue(e.getKey()), toStringValue(e.getValue()));
            }
            out.add(StreamMessage.builder()
                    .id(id)
                    .timestampMs(StreamIdUtils.timestampMs(id))
                    .fields(fields)
                    .build());
        }
        return out;
    }

    private Range<String> buildRange(String from, String to) {
        String fromResolved = from == null || from.isBlank() ? "-" : StreamIdUtils.resolveBoundary(from, false);
        String toResolved = to == null || to.isBlank() ? "+" : StreamIdUtils.resolveBoundary(to, true);
        Range.Bound<String> lower = "-".equals(fromResolved)
                ? Range.Bound.unbounded()
                : Range.Bound.inclusive(fromResolved);
        Range.Bound<String> upper = "+".equals(toResolved)
                ? Range.Bound.unbounded()
                : Range.Bound.inclusive(toResolved);
        return Range.of(lower, upper);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFlatMap(Object reply) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(reply instanceof List<?>)) {
            return result;
        }
        List<Object> list = (List<Object>) reply;
        for (int i = 0; i + 1 < list.size(); i += 2) {
            String key = toStringValue(list.get(i));
            Object value = decodeValue(list.get(i + 1));
            result.put(key, value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseListOfMaps(Object reply) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(reply instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<Object>) reply) {
            result.add(parseFlatMap(item));
        }
        return result;
    }

    private Object decodeValue(Object value) {
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        if (value instanceof List<?>) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) value) {
                out.add(decodeValue(o));
            }
            return out;
        }
        return value;
    }

    private String toStringValue(Object o) {
        if (o == null) return null;
        if (o instanceof byte[]) return new String((byte[]) o, StandardCharsets.UTF_8);
        return String.valueOf(o);
    }

    private long toLong(Object o, long fallback) {
        if (o == null) return fallback;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(toStringValue(o).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

}
