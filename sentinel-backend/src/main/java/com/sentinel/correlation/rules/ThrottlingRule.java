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
 * Rule: External API Throttling / Rate Limiting
 * Triggers when logs show 429 responses from downstream services.
 */
@Component
public class ThrottlingRule implements CorrelationRule {

    @Override
    public String getName() { return "EXTERNAL_API_THROTTLING"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        long throttleCount = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .filter(e -> {
                    String msg = e.message() != null ? e.message().toLowerCase() : "";
                    return msg.contains("rate limit") || msg.contains("429") ||
                            msg.contains("too many requests") || msg.contains("throttle");
                })
                .count();

        if (throttleCount >= 5) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("External API rate limiting detected for " + serviceName +
                    " (" + throttleCount + " throttle events)");
            incident.setSeverity(Severity.P2_HIGH);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
