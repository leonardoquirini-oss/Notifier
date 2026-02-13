package com.containermgmt.tfpgateway.service;

import com.containermgmt.tfpgateway.config.GatewayProperties;
import com.containermgmt.tfpgateway.dto.EventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Fire-and-forget publisher to Valkey streams.
 *
 * Each Artemis address maps to a Valkey stream key (configured in YAML).
 * Failures are logged as warnings and never propagated.
 */
@Service
@Slf4j
public class ValkeyStreamPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public ValkeyStreamPublisher(RedisTemplate<String, String> redisTemplate,
                                 GatewayProperties properties,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publish(EventMessage eventMessage) {
        publish(eventMessage, null);
    }

    public void publish(EventMessage eventMessage, Map<String, Object> metadata) {
        String streamKey = properties.getStreamMapping().get(eventMessage.getEventType());
        if (streamKey == null) {
            log.debug("No stream mapping for eventType={}, skipping Valkey publish",
                    eventMessage.getEventType());
            return;
        }

        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("message_id", eventMessage.getMessageId());
            fields.put("event_type", eventMessage.getEventType());
            fields.put("event_time", eventMessage.getEventTime() != null
                    ? eventMessage.getEventTime().toString()
                    : Instant.now().toString());
            fields.put("payload", eventMessage.getRawPayload());

            if (metadata != null && !metadata.isEmpty()) {
                fields.put("metadata", objectMapper.writeValueAsString(metadata));
            }

            redisTemplate.opsForStream()
                    .add(StreamRecords.string(fields).withStreamKey(streamKey));

            log.info(" -- Published to Valkey stream={}, messageId={}", streamKey, eventMessage.getMessageId());
        } catch (Exception e) {
            log.warn("Failed to publish to Valkey stream={}, messageId={}: {}",
                    streamKey, eventMessage.getMessageId(), e.getMessage());
        }
    }
}
