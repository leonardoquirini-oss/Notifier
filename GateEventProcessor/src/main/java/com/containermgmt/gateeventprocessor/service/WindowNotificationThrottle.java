package com.containermgmt.gateeventprocessor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Valkey-backed "at most once per sliding window" guard, keyed by an arbitrary
 * identifier. Used to suppress duplicate gate notifications when the same unit
 * re-enters the same gate within a short time window.
 */
@Service
@Slf4j
public class WindowNotificationThrottle {

    private static final String KEY_PREFIX = "gateproc:gate:notify:";

    private final RedisTemplate<String, String> redisTemplate;

    public WindowNotificationThrottle(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically claims a notification slot for the identifier, valid for {@code window}.
     *
     * @return true if this is the first claim within the window (caller SHOULD notify);
     *         false if a claim is still live (caller MUST skip).
     *         On Valkey failure returns true (fail-open: better one notification than none).
     */
    public boolean tryAcquire(String identifier, Duration window) {
        if (identifier == null || identifier.isBlank() || window == null || window.isZero() || window.isNegative()) {
            return false;
        }
        String key = KEY_PREFIX + identifier;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", window);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Notification slot acquired: key={}, window={}", key, window);
                return true;
            }
            log.debug("Notification slot still live: key={}", key);
            return false;
        } catch (Exception e) {
            log.warn("Window throttle check failed for key={}: {} — allowing notification", key, e.getMessage());
            return true;
        }
    }
}
