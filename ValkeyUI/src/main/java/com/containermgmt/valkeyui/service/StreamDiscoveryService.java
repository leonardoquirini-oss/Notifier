package com.containermgmt.valkeyui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamDiscoveryService {

    private static final long SCAN_COUNT = 1000L;

    private final RedisTemplate<String, String> redisTemplate;

    public List<String> listStreams(String pattern) {
        String matchPattern = (pattern == null || pattern.isBlank()) ? "*" : pattern;
        ScanOptions options = ScanOptions.scanOptions()
                .match(matchPattern)
                .count(SCAN_COUNT)
                .build();

        List<String> streams = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (isStream(key)) {
                    streams.add(key);
                }
            }
        } catch (Exception e) {
            log.error("Stream discovery failed: {}", e.getMessage(), e);
        }
        streams.sort(String::compareTo);
        return streams;
    }

    public boolean isStream(String key) {
        DataType type = redisTemplate.type(key);
        return type == DataType.STREAM;
    }

}
