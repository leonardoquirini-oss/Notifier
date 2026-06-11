package com.containermgmt.gateeventprocessor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Valkey-backed "at most once per calendar day" guard, keyed by an identifier
 * (e.g. a vehicle plate). Used to avoid re-notifying on every single Position event.
 */
@Service
@Slf4j
public class DailyNotificationThrottle {

    private static final String KEY_PREFIX = "gateproc:geofencing:position:";
    /** Slightly more than a day so the key survives the calendar boundary, then auto-expires. */
    private static final Duration TTL = Duration.ofHours(36);

    private final RedisTemplate<String, String> redisTemplate;

    public DailyNotificationThrottle(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically claims today's notification slot for the identifier.
     *
     * @return true if this is the first claim today (caller SHOULD notify);
     *         false if already claimed today (caller MUST skip).
     *         On Valkey failure returns true (fail-open: better one notification than none).
     */
    public boolean tryAcquireDaily(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        String key = KEY_PREFIX + identifier.trim().toUpperCase(Locale.ROOT) + ":" + LocalDate.now();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", TTL);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Daily slot acquired: key={}", key);
                return true;
            }
            log.debug("Daily slot already taken: key={}", key);
            return false;
        } catch (Exception e) {
            log.warn("Daily throttle check failed for key={}: {} — allowing notification", key, e.getMessage());
            return true;
        }
    }
}
