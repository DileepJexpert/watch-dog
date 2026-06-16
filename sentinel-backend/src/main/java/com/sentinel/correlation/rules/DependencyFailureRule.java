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
 * Rule: Upstream Dependency Failure
 * Triggers when traces show high error span counts suggesting upstream service issues.
 */
@Component
public class DependencyFailureRule implements CorrelationRule {

    @Override
    public String getName() { return "UPSTREAM_DEPENDENCY_FAILURE"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        int maxErrorSpans = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.TRACE)
                .mapToInt(e -> {
                    Object val = e.attributes().get("error_span_count");
                    return val instanceof Number ? ((Number) val).intValue() : 0;
                })
                .max().orElse(0);

        if (maxErrorSpans >= 5) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Upstream dependency failure detected for " + serviceName +
                    " (" + maxErrorSpans + " error spans)");
            incident.setSeverity(Severity.P2_HIGH);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
