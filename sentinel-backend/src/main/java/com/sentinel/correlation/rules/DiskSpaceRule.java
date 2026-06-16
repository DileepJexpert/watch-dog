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
 * Rule: Low Disk Space
 * Triggers when disk usage metrics exceed warning threshold.
 */
@Component
public class DiskSpaceRule implements CorrelationRule {

    @Override
    public String getName() { return "LOW_DISK_SPACE"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        return windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .filter(e -> "disk_percent".equals(e.attributes().get("metric_name")))
                .filter(e -> {
                    Object val = e.attributes().get("metric_value");
                    return val instanceof Number && ((Number) val).doubleValue() > 85.0;
                })
                .findFirst()
                .map(e -> {
                    double usage = ((Number) e.attributes().get("metric_value")).doubleValue();
                    Severity severity = usage > 95 ? Severity.P1_CRITICAL : Severity.P2_HIGH;
                    IncidentEntity incident = new IncidentEntity();
                    incident.setServiceName(serviceName);
                    incident.setTitle(String.format("Low disk space on %s: %.1f%% used", serviceName, usage));
                    incident.setSeverity(severity);
                    incident.setStatus(IncidentStatus.OPEN);
                    incident.setCorrelationRule(getName());
                    incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
                    return incident;
                });
    }
}
