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
 * Rule: Slow Database Queries
 * Triggers when trace spans show DB-related operations exceeding thresholds.
 */
@Component
public class SlowDatabaseQueryRule implements CorrelationRule {

    @Override
    public String getName() { return "SLOW_DATABASE_QUERIES"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean slowDbTrace = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.TRACE)
                .anyMatch(e -> {
                    String op = String.valueOf(e.attributes().getOrDefault("root_operation", ""));
                    Object dur = e.attributes().get("duration_ms");
                    return (op.contains("db") || op.contains("sql") || op.contains("query")) &&
                            dur instanceof Number && ((Number) dur).doubleValue() > 3000;
                });

        boolean dbErrors = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("db_connection_error")));

        if (slowDbTrace && dbErrors) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Slow database queries causing degradation on " + serviceName);
            incident.setSeverity(Severity.P2_HIGH);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
