package com.sentinel.remediation.actions;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.RemediationActionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Opens/closes the circuit breaker for a service by setting a Redis flag.
 * The API gateway / service mesh checks this flag before routing requests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerAction implements RemediationActionExecutor {

    private static final String CB_KEY_PREFIX = "sentinel:circuit-breaker:";
    private static final Set<String> TRIGGERING_RULES = Set.of(
            "CIRCUIT_BREAKER_TRIGGERED",
            "DATABASE_CONNECTIVITY_ISSUE",
            "CASCADING_FAILURE"
    );

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public RemediationActionType getActionType() {
        return RemediationActionType.CIRCUIT_BREAKER_OPEN;
    }

    @Override
    public boolean appliesTo(IncidentEntity incident) {
        return TRIGGERING_RULES.contains(incident.getCorrelationRule());
    }

    @Override
    public boolean execute(IncidentEntity incident, boolean dryRun) {
        String serviceName = incident.getServiceName();
        String key = CB_KEY_PREFIX + serviceName;

        log.warn("AUTO-REMEDIATION [{}]: Opening circuit breaker for {} (dryRun={})",
                incident.getId(), serviceName, dryRun);

        if (dryRun) return true;

        try {
            // Open circuit breaker for 5 minutes (auto-closes after)
            redisTemplate.opsForValue().set(key, "OPEN", Duration.ofMinutes(5));
            return true;
        } catch (Exception e) {
            log.error("Failed to set circuit breaker for {}: {}", serviceName, e.getMessage());
            return false;
        }
    }
}
