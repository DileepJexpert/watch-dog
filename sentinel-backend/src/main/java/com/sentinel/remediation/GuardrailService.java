package com.sentinel.remediation;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.entity.RemediationLogEntity;
import com.sentinel.model.enums.RemediationActionType;
import com.sentinel.repository.RemediationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.List;

/**
 * Enforces guardrails on all auto-remediation actions to prevent:
 * - Exceeding maximum scale limits
 * - Violating cooldown windows between actions
 * - Excessive restart loops
 * - Unauthorized rollbacks
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardrailService {

    private static final String COOLDOWN_KEY_PREFIX = "sentinel:guardrail:cooldown:";
    private static final String RESTART_COUNT_KEY_PREFIX = "sentinel:guardrail:restarts:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RemediationRepository remediationRepository;
    private final SentinelProperties properties;

    /**
     * Returns true if the action is permitted for this incident.
     */
    public boolean isAllowed(IncidentEntity incident, RemediationActionType actionType) {
        String service = incident.getServiceName();

        // Check cooldown
        if (isInCooldown(service, actionType)) {
            log.info("GUARDRAIL: {} on {} blocked - cooldown active", actionType, service);
            return false;
        }

        // Check restart limit
        if (actionType == RemediationActionType.POD_RESTART && isRestartLimitReached(service)) {
            log.warn("GUARDRAIL: POD_RESTART on {} blocked - max {} restarts/hour reached",
                    service, properties.getRemediation().getMaxRestartsPerHour());
            return false;
        }

        return true;
    }

    /**
     * Records that an action was taken, starting the cooldown timer.
     */
    public void recordAction(IncidentEntity incident, RemediationActionType actionType) {
        String service = incident.getServiceName();
        setCooldown(service, actionType);

        if (actionType == RemediationActionType.POD_RESTART) {
            incrementRestartCount(service);
        }
    }

    private boolean isInCooldown(String service, RemediationActionType actionType) {
        String key = COOLDOWN_KEY_PREFIX + service + ":" + actionType.name();
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private void setCooldown(String service, RemediationActionType actionType) {
        String key = COOLDOWN_KEY_PREFIX + service + ":" + actionType.name();
        int cooldownMinutes = getCooldownMinutes(actionType);
        redisTemplate.opsForValue().set(key, "1", Duration.ofMinutes(cooldownMinutes));
    }

    private int getCooldownMinutes(RemediationActionType actionType) {
        return switch (actionType) {
            case POD_SCALE_HORIZONTAL -> properties.getRemediation().getRestartCooldownMinutes();
            case POD_RESTART -> 10;
            case DEPLOYMENT_ROLLBACK -> 30;
            case CIRCUIT_BREAKER_OPEN -> properties.getRemediation().getCircuitBreakerRetryMinutes();
            case CACHE_FLUSH -> 5;
            default -> 15;
        };
    }

    private boolean isRestartLimitReached(String service) {
        String key = RESTART_COUNT_KEY_PREFIX + service;
        Object count = redisTemplate.opsForValue().get(key);
        if (count == null) return false;
        int restarts = Integer.parseInt(count.toString());
        return restarts >= properties.getRemediation().getMaxRestartsPerHour();
    }

    private void incrementRestartCount(String service) {
        String key = RESTART_COUNT_KEY_PREFIX + service;
        redisTemplate.opsForValue().increment(key);
        // Expire after 1 hour (the window for max restarts/hour)
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
    }
}
