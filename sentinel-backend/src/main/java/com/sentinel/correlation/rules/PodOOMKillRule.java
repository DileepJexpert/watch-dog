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
 * Rule: Pod OOMKilled
 * Triggers when OOMKilled events appear in logs AND memory metric is spiking.
 */
@Component
public class PodOOMKillRule implements CorrelationRule {

    @Override
    public String getName() { return "POD_OOM_KILL"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean oomKillLog = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> {
                    String msg = e.message() != null ? e.message().toLowerCase() : "";
                    return msg.contains("oomkilled") || msg.contains("oom killed") || msg.contains("out of memory");
                });

        boolean highMemory = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("high_memory")));

        if (oomKillLog && highMemory) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Pod OOMKilled - memory limit exceeded on " + serviceName);
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
