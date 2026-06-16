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
 * Rule: Memory Leak / Resource Exhaustion
 * Triggers when: Grafana CPU>90% + Kibana OOM errors + Jaeger timeout spans
 * Auto-action: Scale pods horizontally, alert on-call, capture heap dump signal
 */
@Component
public class MemoryLeakRule implements CorrelationRule {

    @Override
    public String getName() {
        return "MEMORY_LEAK_RESOURCE_EXHAUSTION";
    }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean highCpu = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("high_cpu")));

        boolean oomDetected = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("oom_detected")));

        boolean timeoutSpans = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.TRACE)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("timeout_spans")));

        if (highCpu && oomDetected && timeoutSpans) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Memory Leak / Resource Exhaustion on " + serviceName);
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
