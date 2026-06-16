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
 * Rule: Downstream Service Degradation / Cascading Failure
 * Triggers when: Jaeger cascading slow spans + Grafana network I/O spike + Kibana retry storm
 * Auto-action: Enable rate limiting, activate bulkhead pattern, page SRE team
 */
@Component
public class CascadingFailureRule implements CorrelationRule {

    @Override
    public String getName() {
        return "CASCADING_FAILURE";
    }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean cascadingTraces = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.TRACE)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("cascading_failure")));

        boolean networkIoSpike = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("network_io_spike")));

        boolean retryStorm = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("retry_detected")));

        if (cascadingTraces && (networkIoSpike || retryStorm)) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Cascading Failure / Downstream Degradation for " + serviceName);
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
