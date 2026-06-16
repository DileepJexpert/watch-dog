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
 * Rule: TLS Certificate Expiring Soon
 * Triggers when a health probe detects cert expiry < 7 days.
 */
@Component
public class TlsExpiryRule implements CorrelationRule {

    @Override
    public String getName() { return "TLS_CERT_EXPIRY"; }

    @Override
    public Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        return windowEvents.stream()
                .filter(e -> e.signalType() == SignalType.PROBE)
                .filter(e -> Boolean.TRUE.equals(e.attributes().get("tls_cert_expiring_soon")))
                .findFirst()
                .map(e -> {
                    Object days = e.attributes().get("tls_cert_expiry_days");
                    long daysLeft = days instanceof Number ? ((Number) days).longValue() : 0;
                    Severity severity = daysLeft <= 1 ? Severity.P1_CRITICAL : Severity.P2_HIGH;

                    IncidentEntity incident = new IncidentEntity();
                    incident.setServiceName(serviceName);
                    incident.setTitle(String.format("TLS certificate expires in %d days for %s", daysLeft, serviceName));
                    incident.setSeverity(severity);
                    incident.setStatus(IncidentStatus.OPEN);
                    incident.setCorrelationRule(getName());
                    incident.setCorrelatedSignalIds(windowEvents.stream().map(NormalizedEvent::id).toList());
                    return incident;
                });
    }
}
