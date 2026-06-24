package com.sentinel.scheduler;

import com.sentinel.aihub.LlmClient;
import com.sentinel.aihub.model.ChatMessage;
import com.sentinel.aihub.model.LlmOptions;
import com.sentinel.aihub.model.LlmResponse;
import com.sentinel.alerting.EmailNotifier;
import com.sentinel.alerting.SlackNotifier;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.entity.ServiceHealthEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.RemediationRepository;
import com.sentinel.repository.ServiceHealthRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Daily AI-summarized health digest.
 *
 * Runs at {@code sentinel.digest.daily.cron} (default 09:00 UTC), pulls the last
 * {@code lookbackHours} of incidents, service health, and remediation activity,
 * asks the AI Copilot for a human-readable summary in the FR-9 spec shape, and
 * sends it via Slack and Email.
 *
 * Opt-in: only registers when {@code sentinel.digest.daily.enabled=true}.
 * Degrades gracefully — no LlmClient bean simply means a template-built report
 * (no AI commentary) gets sent instead.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sentinel.digest.daily", name = "enabled", havingValue = "true")
public class DailyAiHealthReportScheduler {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private final IncidentRepository incidentRepository;
    private final RemediationRepository remediationRepository;
    private final ServiceHealthRepository serviceHealthRepository;
    private final SlackNotifier slackNotifier;
    private final EmailNotifier emailNotifier;
    private final SentinelProperties properties;
    private final ObjectProvider<LlmClient> llmClientProvider;

    public DailyAiHealthReportScheduler(IncidentRepository incidentRepository,
                                        RemediationRepository remediationRepository,
                                        ServiceHealthRepository serviceHealthRepository,
                                        SlackNotifier slackNotifier,
                                        EmailNotifier emailNotifier,
                                        SentinelProperties properties,
                                        ObjectProvider<LlmClient> llmClientProvider) {
        this.incidentRepository = incidentRepository;
        this.remediationRepository = remediationRepository;
        this.serviceHealthRepository = serviceHealthRepository;
        this.slackNotifier = slackNotifier;
        this.emailNotifier = emailNotifier;
        this.properties = properties;
        this.llmClientProvider = llmClientProvider;
    }

    @Scheduled(cron = "${sentinel.digest.daily.cron:0 0 9 * * *}", zone = "UTC")
    public void sendDailyDigest() {
        log.info("Generating daily AI health digest...");
        try {
            String report = generateReport();
            distribute(report);
        } catch (Exception e) {
            log.error("Failed to send daily digest: {}", e.getMessage(), e);
        }
    }

    /**
     * Generate and return the report without sending — used by the
     * {@code POST /api/digest/daily/trigger} endpoint for on-demand runs.
     */
    public String generateReport() {
        SentinelProperties.DigestConfig.DailyConfig cfg = properties.getDigest().getDaily();
        Instant since = Instant.now().minus(cfg.getLookbackHours(), ChronoUnit.HOURS);
        DigestSnapshot snapshot = buildSnapshot(since);

        String aiCommentary = "";
        if (cfg.isUseAi()) {
            LlmClient llmClient = llmClientProvider.getIfAvailable();
            if (llmClient != null) {
                aiCommentary = askLlm(llmClient, snapshot, cfg.getMaxIncidentsInPrompt());
            } else {
                log.info("AI commentary skipped — LlmClient bean not present (agent disabled?)");
            }
        }

        return formatReport(snapshot, aiCommentary);
    }

    /** Triggers distribution of the freshly generated report. Returns the report body. */
    public String runAndSend() {
        String report = generateReport();
        distribute(report);
        return report;
    }

    private void distribute(String report) {
        SentinelProperties.DigestConfig.DailyConfig cfg = properties.getDigest().getDaily();
        String title = "SENTINEL daily health digest";

        if (cfg.isSlack()) {
            try {
                slackNotifier.sendHealthDigest(title, report);
            } catch (Exception e) {
                log.warn("Slack digest send failed: {}", e.getMessage());
            }
        }

        emailNotifier.sendDailyDigest("[SENTINEL] Daily Health Digest", report,
                cfg.getEmailRecipients());

        if (!cfg.isSlack() && (cfg.getEmailRecipients() == null || cfg.getEmailRecipients().isEmpty())) {
            log.info("No digest distribution channels configured — report follows:\n{}", report);
        }
    }

    private DigestSnapshot buildSnapshot(Instant since) {
        List<IncidentEntity> incidents = incidentRepository.findRecentIncidents(since);

        long open = incidents.stream()
                .filter(i -> i.getStatus() == IncidentStatus.OPEN).count();
        long autoRemediated = incidents.stream().filter(IncidentEntity::isAutoRemediated).count();
        long remediationsTotal = remediationRepository.count();

        Map<String, Long> bySeverity = incidents.stream()
                .collect(Collectors.groupingBy(i -> i.getSeverity().name(), Collectors.counting()));

        List<Map.Entry<String, Long>> topServices = incidents.stream()
                .collect(Collectors.groupingBy(IncidentEntity::getServiceName, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .toList();

        List<Map.Entry<String, Long>> topRules = incidents.stream()
                .filter(i -> i.getCorrelationRule() != null)
                .collect(Collectors.groupingBy(IncidentEntity::getCorrelationRule, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .toList();

        List<ServiceHealthEntity> serviceHealth = serviceHealthRepository.findAll().stream()
                .sorted(Comparator.comparing(ServiceHealthEntity::getServiceName))
                .toList();

        return new DigestSnapshot(since, Instant.now(), incidents, open, autoRemediated,
                remediationsTotal, bySeverity, topServices, topRules, serviceHealth);
    }

    private String askLlm(LlmClient llmClient, DigestSnapshot snapshot, int maxIncidents) {
        String facts = factsBlock(snapshot, maxIncidents);
        List<ChatMessage> messages = List.of(
                ChatMessage.system("""
                        You are SENTINEL's reporting assistant. Given a factual snapshot of
                        the last 24 hours of platform health, produce a 5-7 sentence
                        executive summary suitable for a daily Slack digest. Be specific —
                        cite services and incident counts; do NOT invent data. End with
                        one or two concrete recommended actions for the on-call engineer.
                        Plain text, no markdown headers."""),
                ChatMessage.user("Daily snapshot facts:\n\n" + facts +
                        "\n\nWrite the executive summary now.")
        );
        try {
            LlmResponse response = llmClient.chat(messages, List.of(), LlmOptions.defaults());
            return response.text() == null ? "" : response.text().trim();
        } catch (Exception e) {
            log.warn("LLM digest summarization failed: {}", e.getMessage());
            return "";
        }
    }

    private String factsBlock(DigestSnapshot s, int maxIncidents) {
        StringBuilder sb = new StringBuilder();
        sb.append("Period: ").append(FORMATTER.format(s.since())).append(" to ")
                .append(FORMATTER.format(s.until())).append('\n');
        sb.append("Total incidents: ").append(s.incidents().size())
                .append(" (open: ").append(s.open())
                .append(", auto-remediated: ").append(s.autoRemediated()).append(")\n");
        sb.append("By severity: ").append(s.bySeverity()).append('\n');
        sb.append("Top services by incident count:\n");
        for (var e : s.topServices()) {
            sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("Top correlation rules fired:\n");
        for (var e : s.topRules()) {
            sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("Service-health snapshot:\n");
        for (ServiceHealthEntity h : s.serviceHealth()) {
            sb.append("  - ").append(h.getServiceName()).append(": ").append(h.getStatus()).append('\n');
        }
        sb.append("Recent incidents (most recent first, capped at ").append(maxIncidents).append("):\n");
        int n = Math.min(maxIncidents, s.incidents().size());
        for (int i = 0; i < n; i++) {
            IncidentEntity inc = s.incidents().get(i);
            sb.append("  - [").append(inc.getSeverity()).append("] ")
                    .append(inc.getServiceName()).append(" — ")
                    .append(inc.getTitle()).append(" (rule=").append(inc.getCorrelationRule())
                    .append(", status=").append(inc.getStatus()).append(")\n");
        }
        return sb.toString();
    }

    private String formatReport(DigestSnapshot s, String aiCommentary) {
        StringBuilder sb = new StringBuilder();
        sb.append("SENTINEL DAILY HEALTH DIGEST\n");
        sb.append("============================\n");
        sb.append("Window: ").append(FORMATTER.format(s.since())).append("  ->  ")
                .append(FORMATTER.format(s.until())).append("\n\n");

        if (!aiCommentary.isEmpty()) {
            sb.append("EXECUTIVE SUMMARY (AI)\n");
            sb.append("----------------------\n");
            sb.append(aiCommentary).append("\n\n");
        }

        sb.append("BY THE NUMBERS\n");
        sb.append("--------------\n");
        sb.append(String.format("Total incidents:        %d%n", s.incidents().size()));
        sb.append(String.format("Still open:             %d%n", s.open()));
        sb.append(String.format("Auto-remediated:        %d%n", s.autoRemediated()));
        sb.append(String.format("Lifetime remediations:  %d%n", s.remediationsTotal()));
        sb.append("\n");

        if (!s.bySeverity().isEmpty()) {
            sb.append("BY SEVERITY\n");
            sb.append("-----------\n");
            s.bySeverity().forEach((sev, c) -> sb.append(String.format("  %-12s %d%n", sev, c)));
            sb.append("\n");
        }

        if (!s.topServices().isEmpty()) {
            sb.append("TOP SERVICES BY INCIDENT COUNT\n");
            sb.append("------------------------------\n");
            for (var e : s.topServices()) {
                sb.append(String.format("  %-30s %d%n", e.getKey(), e.getValue()));
            }
            sb.append("\n");
        }

        if (!s.topRules().isEmpty()) {
            sb.append("TOP CORRELATION RULES FIRED\n");
            sb.append("---------------------------\n");
            for (var e : s.topRules()) {
                sb.append(String.format("  %-30s %d%n", e.getKey(), e.getValue()));
            }
            sb.append("\n");
        }

        if (!s.serviceHealth().isEmpty()) {
            sb.append("SERVICE HEALTH STATUS\n");
            sb.append("---------------------\n");
            for (ServiceHealthEntity h : s.serviceHealth()) {
                sb.append(String.format("  %-30s %s%n", h.getServiceName(), h.getStatus()));
            }
        }

        return sb.toString();
    }

    /** Immutable facts pack the LLM and the template both consume. */
    public record DigestSnapshot(
            Instant since,
            Instant until,
            List<IncidentEntity> incidents,
            long open,
            long autoRemediated,
            long remediationsTotal,
            Map<String, Long> bySeverity,
            List<Map.Entry<String, Long>> topServices,
            List<Map.Entry<String, Long>> topRules,
            List<ServiceHealthEntity> serviceHealth
    ) {}
}
