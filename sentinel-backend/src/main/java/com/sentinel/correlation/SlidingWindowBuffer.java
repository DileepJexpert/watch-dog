package com.sentinel.correlation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed sliding window buffer for event correlation.
 * Uses Redis Sorted Sets (ZSET) with event timestamp as score.
 * Each service has its own ZSET key; old events are expired automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowBuffer {

    private static final String KEY_PREFIX = "sentinel:window:";
    private final RedisTemplate<String, Object> redisTemplate;
    private final SentinelProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Adds an event to the sliding window for its service.
     */
    public void add(NormalizedEvent event) {
        String key = KEY_PREFIX + event.serviceName();
        double score = event.timestamp().toEpochMilli();

        try {
            String serialized = objectMapper.writeValueAsString(event);
            redisTemplate.opsForZSet().add(key, serialized, score);
            // Expire the key after 2x window size to prevent unbounded growth
            int windowMinutes = properties.getCorrelation().getWindowMinutes();
            redisTemplate.expire(key, windowMinutes * 2L, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to add event to sliding window for service {}: {}", event.serviceName(), e.getMessage());
        }
    }

    /**
     * Returns all events in the current sliding window for a service.
     */
    public List<NormalizedEvent> getWindow(String serviceName) {
        String key = KEY_PREFIX + serviceName;
        int windowMinutes = properties.getCorrelation().getWindowMinutes();
        long minScore = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES).toEpochMilli();
        long maxScore = Instant.now().toEpochMilli();

        Set<Object> members = redisTemplate.opsForZSet().rangeByScore(key, minScore, maxScore);
        List<NormalizedEvent> events = new ArrayList<>();

        if (members == null) return events;

        for (Object member : members) {
            try {
                NormalizedEvent event = objectMapper.readValue(member.toString(), NormalizedEvent.class);
                events.add(event);
            } catch (Exception e) {
                log.debug("Failed to deserialize event from window: {}", e.getMessage());
            }
        }

        return events;
    }

    /**
     * Returns all service names that currently have events in their window.
     */
    public Set<String> getActiveServices() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null) return Set.of();

        return keys.stream()
                .map(k -> k.substring(KEY_PREFIX.length()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Purges expired entries from a service window.
     */
    public void cleanup(String serviceName) {
        String key = KEY_PREFIX + serviceName;
        int windowMinutes = properties.getCorrelation().getWindowMinutes();
        long minScore = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES).toEpochMilli();
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, minScore);
    }
}
