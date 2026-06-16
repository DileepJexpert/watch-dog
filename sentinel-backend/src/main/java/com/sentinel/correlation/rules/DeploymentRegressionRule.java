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
 * Rule: Deployment Regression
 * Triggers when error rate spikes within 15 minutes of a new deployment event.
 */
@Component
public class DeploymentRegressionRule implements CorrelationRule {

    @Override
    public String getName() { return "DEPLOYMENT_REGRESSION"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        boolean recentDeployment = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.LOG)
                .anyMatch(e -> {
                    String msg = e.message() != null ? e.message().toLowerCase() : "";
                    return msg.contains("deployment") && (msg.contains("rolling") ||
                            msg.contains("deployed") || msg.contains("rollout"));
                });

        boolean errorSpike = windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .anyMatch(e -> Boolean.TRUE.equals(e.attributes().get("high_error_rate")));

        if (recentDeployment && errorSpike) {
            IncidentEntity incident = new IncidentEntity();
            incident.setServiceName(serviceName);
            incident.setTitle("Deployment regression detected for " + serviceName + " - error spike post-deploy");
            incident.setSeverity(Severity.P1_CRITICAL);
            incident.setStatus(IncidentStatus.OPEN);
            incident.setCorrelationRule(getName());
            incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
            return Optional.of(incident);
        }
        return Optional.empty();
    }
}
