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
 * Rule: Message Queue Consumer Lag
 * Triggers when Kafka/queue consumer lag metrics exceed threshold.
 */
@Component
public class MessageQueueBacklogRule implements CorrelationRule {

    private static final long LAG_WARNING_THRESHOLD = 10_000;
    private static final long LAG_CRITICAL_THRESHOLD = 100_000;

    @Override
    public String getName() { return "MESSAGE_QUEUE_BACKLOG"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        return windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.METRIC)
                .filter(e -> "consumer_lag".equals(e.attributes().get("metric_name")))
                .filter(e -> {
                    Object val = e.attributes().get("metric_value");
                    return val instanceof Number && ((Number) val).doubleValue() > LAG_WARNING_THRESHOLD;
                })
                .findFirst()
                .map(e -> {
                    double lag = ((Number) e.attributes().get("metric_value")).doubleValue();
                    Severity severity = lag > LAG_CRITICAL_THRESHOLD ? Severity.P1_CRITICAL : Severity.P2_HIGH;
                    IncidentEntity incident = new IncidentEntity();
                    incident.setServiceName(serviceName);
                    incident.setTitle(String.format("Message queue backlog for %s: %.0f messages behind", serviceName, lag));
                    incident.setSeverity(severity);
                    incident.setStatus(IncidentStatus.OPEN);
                    incident.setCorrelationRule(getName());
                    incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
                    return incident;
                });
    }
}
