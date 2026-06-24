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
 * Rule: Connection Pool Exhaustion
 * Triggers when logs indicate connection pool is at capacity.
 */
@Component
public class ConnectionPoolExhaustionRule implements CorrelationRule {

    private static final long LOG_ONLY_ERROR_THRESHOLD = 3;

    @Override
    public String getName() { return "CONNECTION_POOL_EXHAUSTION"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean poolExhausted = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> {
                    String msg = e.message() != null ? e.message().toLowerCase() : "";
                    return msg.contains("connection pool") && (msg.contains("exhausted") ||
                            msg.contains("timeout") || msg.contains("unable to acquire"));
                });

        boolean highLatency = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("latency_degradation")));

        long dbConnectionErrorCount = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .filter(e -> Boolean.TRUE.equals(e.attributes().get("db_connection_error")))
                .count();

        if ((poolExhausted && highLatency) || dbConnectionErrorCount >= LOG_ONLY_ERROR_THRESHOLD) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Connection pool exhaustion causing latency spikes on " + serviceName);
            incident.setSeverity(highLatency ? Severity.P1_CRITICAL : Severity.P2_HIGH);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
