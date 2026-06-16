package com.sentinel.alerting;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Creates and closes OpsGenie alerts via the Alerts API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsGenieNotifier {

    private final WebClient defaultWebClient;
    private final SentinelProperties properties;

    public void createAlert(IncidentEntity incident) {
        String apiKey = properties.getAlerting().getOpsgenie().getApiKey();
        if (apiKey == null || apiKey.isBlank()) return;

        Map<String, Object> payload = Map.of(
                "message", incident.getTitle(),
                "alias", "sentinel-" + incident.getId(),
                "description", String.format("SENTINEL detected %s on service %s. Rule: %s",
                        incident.getSeverity().getLabel(), incident.getServiceName(),
                        incident.getCorrelationRule()),
                "priority", mapPriority(incident),
                "source", "SENTINEL",
                "tags", List.of("sentinel", incident.getServiceName(), incident.getSeverity().name())
        );

        String alertsUrl = properties.getAlerting().getOpsgenie().getAlertsUrl();
        try {
            defaultWebClient.post()
                    .uri(alertsUrl)
                    .header("Authorization", "GenieKey " + apiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            resp -> log.debug("OpsGenie alert created"),
                            err -> log.warn("OpsGenie alert failed: {}", err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("OpsGenie create alert failed: {}", e.getMessage());
        }
    }

    public void closeAlert(IncidentEntity incident) {
        String apiKey = properties.getAlerting().getOpsgenie().getApiKey();
        if (apiKey == null || apiKey.isBlank()) return;

        String alertsUrl = properties.getAlerting().getOpsgenie().getAlertsUrl();
        String alias = "sentinel-" + incident.getId();

        try {
            defaultWebClient.post()
                    .uri(alertsUrl + "/resolveByAlias?identifier=" + alias)
                    .header("Authorization", "GenieKey " + apiKey)
                    .bodyValue(Map.of("source", "SENTINEL"))
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            resp -> log.debug("OpsGenie alert closed"),
                            err -> log.debug("OpsGenie close alert: {}", err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("OpsGenie close alert failed: {}", e.getMessage());
        }
    }

    private String mapPriority(IncidentEntity incident) {
        return switch (incident.getSeverity()) {
            case P1_CRITICAL -> "P1";
            case P2_HIGH -> "P2";
            case P3_MEDIUM -> "P3";
            default -> "P4";
        };
    }
}
