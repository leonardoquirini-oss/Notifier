package com.containermgmt.valkeyui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.containermgmt.valkeyui.model.ServerInfo;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValkeyServerInfoService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    public ServerInfo getServerInfo() {
        Properties info = collectInfo();
        Map<Integer, Long> dbSizes = collectDbSizes();
        Double pingMs = ping();

        return ServerInfo.builder()
                .version(info.getProperty("redis_version"))
                .mode(info.getProperty("redis_mode"))
                .role(info.getProperty("role"))
                .uptimeSeconds(parseLong(info.getProperty("uptime_in_seconds"), 0L))
                .connectedClients(parseLong(info.getProperty("connected_clients"), 0L))
                .usedMemory(parseLong(info.getProperty("used_memory"), 0L))
                .usedMemoryPeak(parseLong(info.getProperty("used_memory_peak"), 0L))
                .usedMemoryRss(parseLong(info.getProperty("used_memory_rss"), 0L))
                .usedMemoryHuman(info.getProperty("used_memory_human"))
                .usedMemoryPeakHuman(info.getProperty("used_memory_peak_human"))
                .evictedKeys(parseLong(info.getProperty("evicted_keys"), 0L))
                .keyspaceHits(parseLong(info.getProperty("keyspace_hits"), 0L))
                .keyspaceMisses(parseLong(info.getProperty("keyspace_misses"), 0L))
                .dbSizes(dbSizes)
                .pingLatencyMs(pingMs)
                .build();
    }

    public Double ping() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            long start = System.nanoTime();
            connection.ping();
            long durationNs = System.nanoTime() - start;
            return durationNs / 1_000_000.0;
        } catch (Exception e) {
            log.warn("Ping failed: {}", e.getMessage());
            return null;
        }
    }

    public List<String> clientList() {
        return redisTemplate.execute((RedisCallback<List<String>>) connection -> {
            Object reply = connection.execute("CLIENT", "LIST".getBytes(StandardCharsets.UTF_8));
            if (reply == null) {
                return List.of();
            }
            String text = reply instanceof byte[]
                    ? new String((byte[]) reply, StandardCharsets.UTF_8)
                    : reply.toString();
            if (text.isBlank()) {
                return List.of();
            }
            return List.of(text.split("\\r?\\n"));
        });
    }

    private Properties collectInfo() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Properties merged = new Properties();
            for (String section : List.of("server", "memory", "clients", "stats", "replication")) {
                Properties section_info = connection.serverCommands().info(section);
                if (section_info != null) {
                    merged.putAll(section_info);
                }
            }
            return merged;
        } catch (Exception e) {
            log.error("Failed to read INFO: {}", e.getMessage(), e);
            return new Properties();
        }
    }

    private Map<Integer, Long> collectDbSizes() {
        Map<Integer, Long> result = new LinkedHashMap<>();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Properties keyspace = connection.serverCommands().info("keyspace");
            if (keyspace == null) {
                return result;
            }
            for (Map.Entry<Object, Object> entry : keyspace.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.startsWith("db")) continue;
                int dbIdx = parseInt(key.substring(2), -1);
                if (dbIdx < 0) continue;
                String value = String.valueOf(entry.getValue());
                Map<String, String> parts = parseKeyspaceLine(value);
                long keys = parseLong(parts.get("keys"), 0L);
                result.put(dbIdx, keys);
            }
        } catch (Exception e) {
            log.warn("Failed to read keyspace info: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, String> parseKeyspaceLine(String value) {
        Map<String, String> parts = new HashMap<>();
        for (String tok : value.split(",")) {
            int eq = tok.indexOf('=');
            if (eq > 0) {
                parts.put(tok.substring(0, eq).trim(), tok.substring(eq + 1).trim());
            }
        }
        return parts;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

}
