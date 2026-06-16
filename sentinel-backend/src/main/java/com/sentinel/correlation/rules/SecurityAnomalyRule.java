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
 * Rule: Security Anomaly / Unauthorized Access Spike
 * Triggers when logs show a spike in 401/403 responses.
 */
@Component
public class SecurityAnomalyRule implements CorrelationRule {

    @Override
    public String getName() { return "SECURITY_ANOMALY"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        long authFailures = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .filter(e -> {
                    String msg = e.message() != null ? e.message().toLowerCase() : "";
                    return msg.contains("unauthorized") || msg.contains("forbidden") ||
                            msg.contains("authentication failed") || msg.contains("401") ||
                            msg.contains("403");
                })
                .count();

        if (authFailures >= 20) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Security anomaly detected on " + serviceName +
                    " - " + authFailures + " auth failures in 5 minutes");
            incident.setSeverity(Severity.P2_HIGH);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
