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
 * Rule: Unstable Deployment / CrashLoopBackOff
 * Triggers when: Grafana pod restart count>3 + Kibana CrashLoopBackOff logs + Health probe intermittent 503s
 * Auto-action: Auto-rollback to last stable, pause deployment pipeline, alert DevOps lead
 */
@Component
public class CrashLoopRule implements CorrelationRule {

    @Override
    public String getName() {
        return "CRASH_LOOP_UNSTABLE_DEPLOYMENT";
    }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean podRestarts = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("crash_loop_suspected")));

        boolean crashLoopLogs = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> {
                    String msg = e.message() != null ? e.message().toLowerCase() : "";
                    return msg.contains("crashloopbackoff") || msg.contains("crash loop");
                });

        boolean intermittent503 = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.PROBE)
                .anyMatch(e -> {
                    Object code = e.attributes().get("status_code");
                    return code instanceof Number && ((Number) code).intValue() == 503;
                });

        if (podRestarts && (crashLoopLogs || intermittent503)) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Unstable Deployment / CrashLoop detected for " + serviceName);
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
