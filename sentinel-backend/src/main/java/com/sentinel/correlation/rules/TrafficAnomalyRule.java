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
 * Rule: Abnormal Traffic / Request Rate Anomaly
 * Triggers when request rate deviates significantly from normal (flagged by anomaly detection).
 */
@Component
public class TrafficAnomalyRule implements CorrelationRule {

    @Override
    public String getName() { return "TRAFFIC_ANOMALY"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        return windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .filter(e -> Boolean.TRUE.equals(e.attributes().get("anomaly_detected")))
                .filter(e -> "request_rate".equals(e.attributes().get("metric_name")))
                .findFirst()
                .map(e -> {
                    double zScore = e.attributes().get("z_score") instanceof Number
                            ? ((Number) e.attributes().get("z_score")).doubleValue() : 0.0;

                    IncidentEntity incident = new IncidentEntity();
                    incident.setServiceName(serviceName);
                    incident.setTitle(String.format("Traffic anomaly detected for %s (z-score: %.1f)", serviceName, zScore));
                    incident.setSeverity(Severity.P3_MEDIUM);
                    incident.setStatus(IncidentStatus.OPEN);
                    incident.setCorrelationRule(getName());
                    incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
                    return incident;
                });
    }
}
