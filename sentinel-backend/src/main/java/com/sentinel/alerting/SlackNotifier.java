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
 * Sends incident notifications to Slack via Incoming Webhooks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackNotifier {

    private final WebClient defaultWebClient;
    private final SentinelProperties properties;

    public void sendCritical(IncidentEntity incident) {
        sendMessage(buildMessage(incident, ":rotating_light: *P1 CRITICAL*", "#FF0000"));
    }

    public void sendHigh(IncidentEntity incident) {
        sendMessage(buildMessage(incident, ":warning: *P2 HIGH*", "#FF8C00"));
    }

    public void sendMedium(IncidentEntity incident) {
        sendMessage(buildMessage(incident, ":information_source: P3 Medium", "#FFC107"));
    }

    public void sendResolved(IncidentEntity incident) {
        sendMessage(buildMessage(incident, ":white_check_mark: RESOLVED", "#00C851"));
    }

    /**
     * Posts an AI-summarized health digest as a Slack message. Falls through to
     * a no-op when the webhook isn't configured so the scheduler can still run.
     */
    public void sendHealthDigest(String title, String body) {
        Map<String, Object> payload = Map.of(
                "channel", properties.getAlerting().getSlack().getChannel(),
                "attachments", List.of(Map.of(
                        "color", "#2E86AB",
                        "title", title,
                        "text", body,
                        "footer", "SENTINEL daily digest",
                        "mrkdwn_in", List.of("text")
                ))
        );
        sendMessage(payload);
    }

    private Map<String, Object> buildMessage(IncidentEntity incident, String prefix, String color) {
        String text = String.format("%s - %s\n*Service:* %s\n*Incident:* %s\n*Rule:* %s",
                prefix, incident.getSeverity().getLabel(),
                incident.getServiceName(), incident.getTitle(),
                incident.getCorrelationRule());

        return Map.of(
                "channel", properties.getAlerting().getSlack().getChannel(),
                "attachments", List.of(Map.of(
                        "color", color,
                        "text", text,
                        "footer", "SENTINEL | " + incident.getDetectedAt(),
                        "mrkdwn_in", List.of("text")
                ))
        );
    }

    private void sendMessage(Map<String, Object> payload) {
        String webhookUrl = properties.getAlerting().getSlack().getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Slack webhook not configured, skipping notification");
            return;
        }

        try {
            defaultWebClient.post()
                    .uri(webhookUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            resp -> log.debug("Slack notification sent"),
                            err -> log.warn("Slack notification failed: {}", err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("Failed to send Slack notification: {}", e.getMessage());
        }
    }
}
