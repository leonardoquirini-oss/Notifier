package com.containermgmt.valkeyui.service;

import com.containermgmt.valkeyui.config.ValkeyUiProperties;
import com.containermgmt.valkeyui.model.ConsumerGroupInfo;
import com.containermgmt.valkeyui.model.StreamSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "valkeyui.sampler", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class StreamSamplerService {

    private final StreamDiscoveryService discoveryService;
    private final StreamInspectionService inspectionService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ValkeyUiProperties properties;

    @Value("${valkeyui.sampler.metrics-stream-prefix:valkeyui:metrics:stream:}")
    private String metricsPrefix;

    @Scheduled(fixedDelayString = "${valkeyui.sampler.interval-ms:60000}",
            initialDelayString = "${valkeyui.sampler.initial-delay-ms:30000}")
    public void sample() {
        List<String> streams = discoveryService.listStreams("*");
        long now = System.currentTimeMillis();
        for (String key : streams) {
            if (key.startsWith(metricsPrefix)) {
                continue;
            }
            if (key.equals(properties.getAudit().getStreamKey())) {
                continue;
            }
            try {
                sampleSingle(key, now);
            } catch (Exception e) {
                log.warn("Sampling failed for stream {}: {}", key, e.getMessage());
            }
        }
    }

    private void sampleSingle(String key, long now) {
        StreamSummary summary = inspectionService.summary(key);
        List<ConsumerGroupInfo> groups = inspectionService.consumerGroups(key);
        long pendingTotal = groups.stream().mapToLong(ConsumerGroupInfo::getPending).sum();

        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("length", String.valueOf(summary.getLength()));
        entry.put("first_id", summary.getFirstId() == null ? "" : summary.getFirstId());
        entry.put("last_id", summary.getLastId() == null ? "" : summary.getLastId());
        entry.put("pending_total", String.valueOf(pendingTotal));
        entry.put("memory_bytes", summary.getMemoryBytes() == null ? "" : summary.getMemoryBytes().toString());
        entry.put("sampled_at", String.valueOf(now));

        String metricsKey = metricsPrefix + key;
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        MapRecord<String, String, String> record =
                StreamRecords.mapBacked(entry).withStreamKey(metricsKey);
        ops.add(record);

        long retention = properties.getSampler().getMetricsRetention();
        if (retention > 0) {
            try {
                ops.trim(metricsKey, retention, true);
            } catch (Exception ignored) {
                // best-effort trim
            }
        }
    }

}
