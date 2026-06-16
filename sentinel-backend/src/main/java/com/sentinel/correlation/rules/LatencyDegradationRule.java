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
 * Rule: P99 Latency Degradation
 * Triggers when P99 latency exceeds 5 seconds.
 */
@Component
public class LatencyDegradationRule implements CorrelationRule {

    private static final double P99_CRITICAL_MS = 5000;
    private static final double P95_HIGH_MS = 3000;

    @Override
    public String getName() { return "LATENCY_DEGRADATION"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        double maxP99 = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .mapToDouble(e -> {
                    Object val = e.attributes().get("latency_p99_ms");
                    return val instanceof Number ? ((Number) val).doubleValue() : 0.0;
                })
                .max().orElse(0.0);

        double maxP95 = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .mapToDouble(e -> {
                    Object val = e.attributes().get("latency_p95_ms");
                    return val instanceof Number ? ((Number) val).doubleValue() : 0.0;
                })
                .max().orElse(0.0);

        if (maxP99 >= P99_CRITICAL_MS || maxP95 >= P95_HIGH_MS) {
            Severity severity = maxP99 >= P99_CRITICAL_MS ? Severity.P1_CRITICAL : Severity.P2_HIGH;
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle(String.format("Latency degradation P95=%.0fms P99=%.0fms for %s",
                    maxP95, maxP99, serviceName));
            incident.setSeverity(severity);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
