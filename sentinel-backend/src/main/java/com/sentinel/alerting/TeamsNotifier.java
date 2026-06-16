package com.sentinel.alerting;

import com.sentinel.config.SentinelProperties;
import com.sentinel.ingestion.ElasticsearchConnector;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.entity.ServiceHealthEntity;
import com.sentinel.repository.ServiceHealthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends incident notifications to Microsoft Teams via Adaptive Cards.
 *
 * Uses the Power Automate webhook format (application/vnd.microsoft.card.adaptive),
 * which is compatible with both new Workflow webhooks and legacy Office 365 connectors.
 *
 * Routing: each service can be mapped to its own Teams channel webhook URL via
 * sentinel.alerting.teams.channel-map. Unknown services fall back to
 * sentinel.alerting.teams.default-webhook-url.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamsNotifier {

    private static final int STACK_TRACE_MAX_LINES = 20;
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private final WebClient defaultWebClient;
    private final SentinelProperties properties;
    private final ServiceHealthRepository serviceHealthRepository;
    private final ElasticsearchConnector elasticsearchConnector;

    // ── Public send methods ────────────────────────────────────────────────────

    public void sendCritical(IncidentEntity incident) {
        send(incident, "🚨 P1 CRITICAL", "Attention");
    }

    public void sendHigh(IncidentEntity incident) {
        send(incident, "⚠️ P2 HIGH", "Warning");
    }

    public void sendMedium(IncidentEntity incident) {
        send(incident, "ℹ️ P3 MEDIUM", "Accent");
    }

    public void sendResolved(IncidentEntity incident) {
        send(incident, "✅ RESOLVED", "Good");
    }

    // ── Core dispatch ──────────────────────────────────────────────────────────

    private void send(IncidentEntity incident, String severityLabel, String color) {
        String webhookUrl = resolveWebhookUrl(incident.getServiceName());
        if (webhookUrl == null) {
            log.debug("Teams webhook not configured for service {}, skipping", incident.getServiceName());
            return;
        }

        Map<String, Object> payload = buildAdaptiveCardPayload(incident, severityLabel, color);

        try {
            defaultWebClient.post()
                    .uri(webhookUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            resp -> log.debug("Teams notification sent for incident {}", incident.getId()),
                            err  -> log.warn("Teams notification failed for incident {}: {}",
                                    incident.getId(), err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("Failed to dispatch Teams notification for incident {}: {}", incident.getId(), e.getMessage());
        }
    }

    // ── Webhook URL resolution (per-service routing) ───────────────────────────

    private String resolveWebhookUrl(String serviceName) {
        SentinelProperties.AlertingConfig.TeamsConfig cfg = properties.getAlerting().getTeams();
        Map<String, String> channelMap = cfg.getChannelMap();

        String url = channelMap.getOrDefault(serviceName, cfg.getDefaultWebhookUrl());
        return (url == null || url.isBlank()) ? null : url;
    }

    // ── Adaptive Card builder ──────────────────────────────────────────────────

    private Map<String, Object> buildAdaptiveCardPayload(
            IncidentEntity incident, String severityLabel, String accentColor) {

        SentinelProperties.AlertingConfig.TeamsConfig cfg = properties.getAlerting().getTeams();

        // Fetch service health metrics (best-effort)
        Optional<ServiceHealthEntity> healthOpt =
                serviceHealthRepository.findById(incident.getServiceName());

        String errorRate = healthOpt.map(h -> String.format("%.2f%%", h.getErrorRate()))
                .orElse("N/A");
        String latencyP95 = healthOpt.map(h -> String.format("%.0f ms", h.getLatencyP95()))
                .orElse("N/A");

        // Fetch stack trace from Elasticsearch (best-effort)
        String stackTrace = elasticsearchConnector
                .fetchLatestStackTrace(incident.getServiceName(), STACK_TRACE_MAX_LINES);

        // Header text
        String headerText = String.format("%s — %s", severityLabel, incident.getServiceName());

        // Auto-remediation description
        String autoRemediatedValue = incident.isAutoRemediated() ? "Yes" : "No";

        // Fact set
        List<Map<String, String>> facts = new ArrayList<>();
        facts.add(fact("Service",         incident.getServiceName()));
        facts.add(fact("Title",           incident.getTitle()));
        facts.add(fact("Correlation Rule", nullSafe(incident.getCorrelationRule(), "—")));
        facts.add(fact("Severity",        incident.getSeverity().name()));
        facts.add(fact("Status",          incident.getStatus().name()));
        facts.add(fact("Error Rate",      errorRate));
        facts.add(fact("P95 Latency",     latencyP95));
        facts.add(fact("Auto-Remediated", autoRemediatedValue));
        facts.add(fact("Detected At",     TIMESTAMP_FMT.format(incident.getDetectedAt())));
        facts.add(fact("Incident ID",     incident.getId().toString()));

        // Card body elements
        List<Map<String, Object>> bodyElements = new ArrayList<>();

        // Title bar (colored by severity)
        Map<String, Object> titleBlock = new LinkedHashMap<>();
        titleBlock.put("type",   "TextBlock");
        titleBlock.put("text",   headerText);
        titleBlock.put("weight", "Bolder");
        titleBlock.put("size",   "Large");
        titleBlock.put("color",  accentColor);
        titleBlock.put("wrap",   true);
        bodyElements.add(titleBlock);

        // Fact set
        Map<String, Object> factSet = new LinkedHashMap<>();
        factSet.put("type",  "FactSet");
        factSet.put("facts", facts);
        bodyElements.add(factSet);

        // Stack trace (only if available)
        if (!stackTrace.isBlank()) {
            Map<String, Object> stHeader = new LinkedHashMap<>();
            stHeader.put("type",   "TextBlock");
            stHeader.put("text",   "Recent Stack Trace");
            stHeader.put("weight", "Bolder");
            stHeader.put("separator", true);
            bodyElements.add(stHeader);

            Map<String, Object> stBody = new LinkedHashMap<>();
            stBody.put("type",     "TextBlock");
            stBody.put("text",     stackTrace);
            stBody.put("wrap",     true);
            stBody.put("fontType", "Monospace");
            stBody.put("size",     "Small");
            bodyElements.add(stBody);
        }

        // Action buttons — deep links to observability tools
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(openUrlAction("View in Kibana",
                buildKibanaUrl(cfg.getKibanaBaseUrl(), incident.getServiceName())));
        actions.add(openUrlAction("View in Grafana",
                buildGrafanaUrl(cfg.getGrafanaBaseUrl(), incident.getServiceName())));
        actions.add(openUrlAction("View in Jaeger",
                buildJaegerUrl(cfg.getJaegerBaseUrl(), incident.getServiceName())));

        // Adaptive Card content
        Map<String, Object> cardContent = new LinkedHashMap<>();
        cardContent.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        cardContent.put("type",    "AdaptiveCard");
        cardContent.put("version", "1.4");
        cardContent.put("msteams", Map.of("width", "Full"));
        cardContent.put("body",    bodyElements);
        cardContent.put("actions", actions);

        // Attachment wrapper (Power Automate / new Teams webhook format)
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("content",     cardContent);

        // Top-level message
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type",        "message");
        message.put("attachments", List.of(attachment));
        return message;
    }

    // ── Deep-link URL builders ─────────────────────────────────────────────────

    private String buildKibanaUrl(String base, String serviceName) {
        return String.format(
                "%s/app/discover#/?_a=(query:(language:kuery,query:'service.name:\"%s\"'))",
                base, serviceName);
    }

    private String buildGrafanaUrl(String base, String serviceName) {
        return String.format(
                "%s/explore?orgId=1&left={\"queries\":[{\"expr\":\"rate(http_requests_total{job=\\\"%s\\\"}[5m])\"}]}",
                base, serviceName);
    }

    private String buildJaegerUrl(String base, String serviceName) {
        return String.format("%s/search?service=%s&limit=20", base, serviceName);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, String> fact(String title, String value) {
        Map<String, String> f = new HashMap<>();
        f.put("title", title);
        f.put("value", value);
        return f;
    }

    private Map<String, Object> openUrlAction(String title, String url) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type",  "Action.OpenUrl");
        action.put("title", title);
        action.put("url",   url);
        return action;
    }

    private String nullSafe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
