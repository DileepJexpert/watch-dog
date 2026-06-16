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
 * Rule: Database Connectivity Issue
 * Triggers when: Health probe 5xx spike + Kibana DB connection errors + Grafana DB latency spike
 * Auto-action: Trigger DB failover check, alert DBA team, enable circuit breaker
 */
@Component
public class DatabaseConnectivityRule implements CorrelationRule {

    @Override
    public String getName() {
        return "DATABASE_CONNECTIVITY_ISSUE";
    }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean probeErrors = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.PROBE)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("5xx_error")));

        boolean dbConnectionErrors = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("db_connection_error")));

        boolean dbLatencySpike = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> {
                    Object latency = e.attributes().get("latency_p95_ms");
                    return latency instanceof Number && ((Number) latency).doubleValue() > 2000;
                });

        if (probeErrors && dbConnectionErrors && dbLatencySpike) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Database Connectivity Issue affecting " + serviceName);
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
