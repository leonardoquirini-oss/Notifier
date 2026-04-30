package com.containermgmt.valkeyui.service;

import com.containermgmt.valkeyui.config.ValkeyUiProperties;
import com.containermgmt.valkeyui.model.AuditEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ValkeyUiProperties properties;

    public List<AuditEntry> recent(int limit) {
        String key = properties.getAudit().getStreamKey();
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        Range<String> range = Range.unbounded();
        org.springframework.data.redis.connection.Limit l =
                org.springframework.data.redis.connection.Limit.limit().count(Math.max(1, limit));
        List<MapRecord<String, Object, Object>> records;
        try {
            records = ops.reverseRange(key, range, l);
        } catch (Exception e) {
            log.warn("Audit stream read failed: {}", e.getMessage());
            return List.of();
        }
        if (records == null) return List.of();

        List<AuditEntry> out = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> r : records) {
            RecordId rid = r.getId();
            String id = rid == null ? "" : rid.getValue();
            Map<Object, Object> v = r.getValue();
            out.add(AuditEntry.builder()
                    .id(id)
                    .timestampMs(StreamIdUtils.timestampMs(id))
                    .user(asString(v.get("user")))
                    .operation(asString(v.get("operation")))
                    .stream(asString(v.get("stream")))
                    .params(asString(v.get("params")))
                    .result(asString(v.get("result")))
                    .durationMs(parseLong(v.get("duration_ms")))
                    .build());
        }
        return out;
    }

    private String asString(Object o) {
        if (o == null) return null;
        if (o instanceof byte[]) return new String((byte[]) o, StandardCharsets.UTF_8);
        return o.toString();
    }

    private Long parseLong(Object o) {
        if (o == null) return null;
        try {
            return Long.parseLong(asString(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

}
