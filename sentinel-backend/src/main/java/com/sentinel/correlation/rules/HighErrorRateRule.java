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
 * Rule: Sustained High HTTP Error Rate
 * Triggers when error rate metric exceeds threshold.
 */
@Component
public class HighErrorRateRule implements CorrelationRule {

    private static final double ERROR_RATE_P1_THRESHOLD = 10.0;
    private static final double ERROR_RATE_P2_THRESHOLD = 1.0;

    @Override
    public String getName() { return "HIGH_ERROR_RATE"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        double maxErrorRate = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .mapToDouble(e -> {
                    Object val = e.attributes().get("metric_value");
                    if (val instanceof Number && "error_rate_percent".equals(e.attributes().get("metric_name"))) {
                        return ((Number) val).doubleValue();
                    }
                    return 0.0;
                })
                .max().orElse(0.0);

        if (maxErrorRate >= ERROR_RATE_P2_THRESHOLD) {
            Severity severity = maxErrorRate >= ERROR_RATE_P1_THRESHOLD ? Severity.P1_CRITICAL : Severity.P2_HIGH;
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle(String.format("High HTTP error rate %.1f%% for %s", maxErrorRate, serviceName));
            incident.setSeverity(severity);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
