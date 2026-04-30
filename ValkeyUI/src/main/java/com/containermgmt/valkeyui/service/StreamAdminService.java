package com.containermgmt.valkeyui.service;

import com.containermgmt.valkeyui.audit.AuditLogger;
import com.containermgmt.valkeyui.config.ValkeyUiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamAdminService {

    private static final int DELETE_BATCH_SIZE = 500;

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final ValkeyUiProperties properties;
    private final AuditLogger auditLogger;

    public Map<String, Object> trim(String key, String strategy, String value, boolean approximate) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("strategy", strategy);
        params.put("value", value);
        params.put("approximate", approximate);

        try {
            long lengthBefore = lengthOrZero(key);
            long trimmed = executeTrim(key, strategy, value, approximate);
            long lengthAfter = lengthOrZero(key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("trimmed", trimmed);
            result.put("lengthBefore", lengthBefore);
            result.put("lengthAfter", lengthAfter);
            auditLogger.log("TRIM", key, params, "OK", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.log("TRIM", key, params, "ERROR: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> deleteRange(String key, String fromId, String toId) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fromId", fromId);
        params.put("toId", toId);

        try {
            long deleted = deleteIdsInRange(key, fromId, toId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", deleted);
            auditLogger.log("DELETE_RANGE", key, params, "OK", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.log("DELETE_RANGE", key, params, "ERROR: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> deleteTimeRange(String key, String fromIso, String toIso) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fromTime", fromIso);
        params.put("toTime", toIso);

        try {
            String fromId = StreamIdUtils.lowerBoundFromInstant(Instant.parse(fromIso));
            String toId = StreamIdUtils.upperBoundFromInstant(Instant.parse(toIso));
            long deleted = deleteIdsInRange(key, fromId, toId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", deleted);
            result.put("fromId", fromId);
            result.put("toId", toId);
            auditLogger.log("DELETE_TIME_RANGE", key, params, "OK", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.log("DELETE_TIME_RANGE", key, params, "ERROR: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> deleteAll(String key) {
        ensureWritable();
        long start = System.currentTimeMillis();
        try {
            Boolean deleted = redisTemplate.delete(key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted", Boolean.TRUE.equals(deleted));
            auditLogger.log("DELETE_ALL", key, Map.of(), "OK", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            auditLogger.log("DELETE_ALL", key, Map.of(), "ERROR: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> createGroup(String key, String groupName, String id, boolean mkstream) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("group", groupName);
        params.put("id", id);
        params.put("mkstream", mkstream);

        try (RedisConnection connection = connectionFactory.getConnection()) {
            List<byte[]> args = new ArrayList<>();
            args.add("CREATE".getBytes(StandardCharsets.UTF_8));
            args.add(key.getBytes(StandardCharsets.UTF_8));
            args.add(groupName.getBytes(StandardCharsets.UTF_8));
            args.add(id.getBytes(StandardCharsets.UTF_8));
            if (mkstream) {
                args.add("MKSTREAM".getBytes(StandardCharsets.UTF_8));
            }
            connection.execute("XGROUP", args.toArray(new byte[0][]));
            auditLogger.log("XGROUP_CREATE", key, params, "OK", System.currentTimeMillis() - start);
            return Map.of("created", true);
        } catch (Exception e) {
            auditLogger.log("XGROUP_CREATE", key, params, "ERROR: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> destroyGroup(String key, String groupName) {
        ensureWritable();
        long start = System.currentTimeMillis();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Object reply = connection.execute("XGROUP",
                    "DESTROY".getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8),
                    groupName.getBytes(StandardCharsets.UTF_8));
            long destroyed = reply instanceof Number ? ((Number) reply).longValue() : 0L;
            auditLogger.log("XGROUP_DESTROY", key, Map.of("group", groupName),
                    "OK", System.currentTimeMillis() - start);
            return Map.of("destroyed", destroyed);
        } catch (Exception e) {
            auditLogger.log("XGROUP_DESTROY", key, Map.of("group", groupName),
                    "ERROR: " + e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> deleteConsumer(String key, String groupName, String consumer) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = Map.of("group", groupName, "consumer", consumer);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Object reply = connection.execute("XGROUP",
                    "DELCONSUMER".getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8),
                    groupName.getBytes(StandardCharsets.UTF_8),
                    consumer.getBytes(StandardCharsets.UTF_8));
            long pending = reply instanceof Number ? ((Number) reply).longValue() : 0L;
            auditLogger.log("XGROUP_DELCONSUMER", key, params, "OK",
                    System.currentTimeMillis() - start);
            return Map.of("pendingRemoved", pending);
        } catch (Exception e) {
            auditLogger.log("XGROUP_DELCONSUMER", key, params,
                    "ERROR: " + e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> setGroupId(String key, String groupName, String id) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = Map.of("group", groupName, "id", id);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.execute("XGROUP",
                    "SETID".getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8),
                    groupName.getBytes(StandardCharsets.UTF_8),
                    id.getBytes(StandardCharsets.UTF_8));
            auditLogger.log("XGROUP_SETID", key, params, "OK",
                    System.currentTimeMillis() - start);
            return Map.of("ok", true);
        } catch (Exception e) {
            auditLogger.log("XGROUP_SETID", key, params,
                    "ERROR: " + e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    public Map<String, Object> ack(String key, String groupName, List<String> ids) {
        ensureWritable();
        long start = System.currentTimeMillis();
        Map<String, Object> params = Map.of("group", groupName, "ids", ids);
        try {
            StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
            Long acked = ops.acknowledge(key, groupName, ids.toArray(new String[0]));
            auditLogger.log("XACK", key, params, "OK", System.currentTimeMillis() - start);
            return Map.of("acked", acked == null ? 0 : acked);
        } catch (Exception e) {
            auditLogger.log("XACK", key, params,
                    "ERROR: " + e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    private long executeTrim(String key, String strategy, String value, boolean approximate) {
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        if ("MAXLEN".equalsIgnoreCase(strategy)) {
            long maxLen = Long.parseLong(value);
            Long trimmed = ops.trim(key, maxLen, approximate);
            return trimmed == null ? 0L : trimmed;
        }
        if ("MINID".equalsIgnoreCase(strategy)) {
            return executeMinIdTrim(key, value, approximate);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown trim strategy: " + strategy);
    }

    private long executeMinIdTrim(String key, String minId, boolean approximate) {
        return redisTemplate.execute((RedisCallback<Long>) connection -> {
            List<byte[]> args = new ArrayList<>();
            args.add(key.getBytes(StandardCharsets.UTF_8));
            args.add("MINID".getBytes(StandardCharsets.UTF_8));
            if (approximate) {
                args.add("~".getBytes(StandardCharsets.UTF_8));
            }
            args.add(minId.getBytes(StandardCharsets.UTF_8));
            Object reply = connection.execute("XTRIM", args.toArray(new byte[0][]));
            return reply instanceof Number ? ((Number) reply).longValue() : 0L;
        });
    }

    private long deleteIdsInRange(String key, String fromId, String toId) {
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Range.Bound<String> lower = "-".equals(fromId)
                ? Range.Bound.unbounded()
                : Range.Bound.inclusive(StreamIdUtils.resolveBoundary(fromId, false));
        Range.Bound<String> upper = "+".equals(toId)
                ? Range.Bound.unbounded()
                : Range.Bound.inclusive(StreamIdUtils.resolveBoundary(toId, true));
        Range<String> range = Range.of(lower, upper);

        long maxCall = properties.getQuery().getMaxMessagesPerCall();
        long deleted = 0;
        while (true) {
            org.springframework.data.redis.connection.Limit limit =
                    org.springframework.data.redis.connection.Limit.limit().count((int) Math.min(maxCall, Integer.MAX_VALUE));
            List<MapRecord<String, Object, Object>> records = ops.range(key, range, limit);
            if (records == null || records.isEmpty()) {
                break;
            }
            List<String> ids = new ArrayList<>(records.size());
            for (MapRecord<String, Object, Object> r : records) {
                RecordId rid = r.getId();
                if (rid != null) ids.add(rid.getValue());
            }
            for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
                List<String> batch = ids.subList(i, Math.min(i + DELETE_BATCH_SIZE, ids.size()));
                Long del = ops.delete(key, batch.toArray(new String[0]));
                if (del != null) deleted += del;
            }
            if (records.size() < maxCall) {
                break;
            }
        }
        return deleted;
    }

    private long lengthOrZero(String key) {
        try {
            Long len = redisTemplate.opsForStream().size(key);
            return len == null ? 0L : len;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void ensureWritable() {
        if (properties.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Application is in read-only mode");
        }
    }

}
