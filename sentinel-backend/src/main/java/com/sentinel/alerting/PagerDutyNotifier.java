package com.sentinel.alerting;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Triggers PagerDuty incidents via Events API v2.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PagerDutyNotifier {

    private final WebClient defaultWebClient;
    private final SentinelProperties properties;

    public void triggerIncident(IncidentEntity incident) {
        String integrationKey = properties.getAlerting().getPagerduty().getIntegrationKey();
        if (integrationKey == null || integrationKey.isBlank()) return;

        Map<String, Object> payload = Map.of(
                "routing_key", integrationKey,
                "event_action", "trigger",
                "dedup_key", "sentinel-" + incident.getId().toString(),
                "payload", Map.of(
                        "summary", incident.getTitle(),
                        "severity", mapSeverity(incident),
                        "source", incident.getServiceName(),
                        "custom_details", Map.of(
                                "correlation_rule", incident.getCorrelationRule(),
                                "incident_id", incident.getId().toString(),
                                "auto_remediated", incident.isAutoRemediated()
                        )
                )
        );

        String eventsUrl = properties.getAlerting().getPagerduty().getEventsUrl();
        post(eventsUrl, payload, "PagerDuty trigger");
    }

    public void resolveIncident(IncidentEntity incident) {
        String integrationKey = properties.getAlerting().getPagerduty().getIntegrationKey();
        if (integrationKey == null || integrationKey.isBlank()) return;

        Map<String, Object> payload = Map.of(
                "routing_key", integrationKey,
                "event_action", "resolve",
                "dedup_key", "sentinel-" + incident.getId().toString()
        );

        String eventsUrl = properties.getAlerting().getPagerduty().getEventsUrl();
        post(eventsUrl, payload, "PagerDuty resolve");
    }

    private String mapSeverity(IncidentEntity incident) {
        return switch (incident.getSeverity()) {
            case P1_CRITICAL -> "critical";
            case P2_HIGH -> "error";
            case P3_MEDIUM -> "warning";
            default -> "info";
        };
    }

    private void post(String url, Map<String, Object> payload, String action) {
        try {
            defaultWebClient.post()
                    .uri(url)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            resp -> log.debug("{} sent", action),
                            err -> log.warn("{} failed: {}", action, err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("{} failed: {}", action, e.getMessage());
        }
    }
}
