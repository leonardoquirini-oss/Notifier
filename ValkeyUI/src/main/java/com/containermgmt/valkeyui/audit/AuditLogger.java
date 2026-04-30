package com.containermgmt.valkeyui.audit;

import com.containermgmt.valkeyui.config.ValkeyUiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogger {

    private final RedisTemplate<String, String> redisTemplate;
    private final ValkeyUiProperties properties;
    private final ObjectMapper objectMapper;

    public void log(String operation, String stream, Map<String, Object> params,
                    String result, long durationMs) {
        String user = currentUser();
        String paramsJson = serializeParams(params);

        log.info("[AUDIT] user={} op={} stream={} params={} result={} duration_ms={}",
                user, operation, stream, paramsJson, result, durationMs);

        try {
            writeAuditStream(user, operation, stream, paramsJson, result, durationMs);
        } catch (Exception e) {
            log.error("Failed to write audit entry to stream {}: {}",
                    properties.getAudit().getStreamKey(), e.getMessage(), e);
        }
    }

    private void writeAuditStream(String user, String operation, String stream,
                                  String paramsJson, String result, long durationMs) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("user", user);
        entry.put("operation", operation);
        entry.put("stream", stream == null ? "" : stream);
        entry.put("params", paramsJson);
        entry.put("result", result);
        entry.put("duration_ms", String.valueOf(durationMs));
        entry.put("timestamp_ms", String.valueOf(System.currentTimeMillis()));

        String streamKey = properties.getAudit().getStreamKey();
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        MapRecord<String, String, String> record =
                StreamRecords.mapBacked(entry).withStreamKey(streamKey);
        ops.add(record);

        Long retention = properties.getAudit().getRetention();
        if (retention != null && retention > 0) {
            try {
                ops.trim(streamKey, retention, true);
            } catch (Exception ignored) {
                // best-effort trim
            }
        }
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return "anonymous";
        }
        return auth.getName();
    }

    private String serializeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return params.toString();
        }
    }

}
