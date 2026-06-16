package com.sentinel.correlation.rules;

import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.Severity;
import com.sentinel.model.enums.SignalType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Rule: Circuit Breaker Tripped
 * Triggers when 5xx downstream rate exceeds 50% for sustained period.
 */
@Component
public class CircuitBreakerOpenRule implements CorrelationRule {

    @Override
    public String getName() { return "CIRCUIT_BREAKER_TRIGGERED"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean high5xx = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("5xx_spike")));

        long probeFailureCount = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.PROBE)
                .filter(e -> Boolean.TRUE.equals(e.attributes().get("5xx_error")))
                .count();

        if (high5xx && probeFailureCount >= 2) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Circuit breaker condition triggered for " + serviceName + " - downstream 5xx >50%");
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
