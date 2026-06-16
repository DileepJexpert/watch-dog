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
 * Rule: Service Completely Down
 * Triggers when health probes report the service as unreachable.
 */
@Component
public class ServiceDownRule implements CorrelationRule {

    @Override
    public String getName() { return "SERVICE_DOWN"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean serviceUnreachable = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.PROBE)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("service_down")));

        if (serviceUnreachable) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Service DOWN - " + serviceName + " is unreachable");
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
