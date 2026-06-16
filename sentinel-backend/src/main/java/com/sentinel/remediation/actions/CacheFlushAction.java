package com.sentinel.remediation.actions;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.RemediationActionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Flushes the cache for a specific service (scoped, never global).
 * Safe idempotent action triggered by stale data errors.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheFlushAction implements RemediationActionExecutor {

    private static final String CACHE_KEY_PREFIX = "cache:";
    private static final Set<String> TRIGGERING_RULES = Set.of(
            "DEPLOYMENT_REGRESSION",
            "HIGH_ERROR_RATE"
    );

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public RemediationActionType getActionType() {
        return RemediationActionType.CACHE_FLUSH;
    }

    @Override
    public boolean appliesTo(IncidentEntity incident) {
        // Only flush cache if error message indicates stale data
        return TRIGGERING_RULES.contains(incident.getCorrelationRule())
                && incident.getTitle() != null
                && incident.getTitle().toLowerCase().contains("stale");
    }

    @Override
    public boolean execute(IncidentEntity incident, boolean dryRun) {
        String serviceName = incident.getServiceName();
        String keyPattern = CACHE_KEY_PREFIX + serviceName + ":*";

        log.info("AUTO-REMEDIATION [{}]: Flushing cache for {} (pattern: {}, dryRun={})",
                incident.getId(), serviceName, keyPattern, dryRun);

        if (dryRun) return true;

        try {
            Set<String> keys = redisTemplate.keys(keyPattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Flushed {} cache keys for service {}", keys.size(), serviceName);
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to flush cache for {}: {}", serviceName, e.getMessage());
            return false;
        }
    }
}
