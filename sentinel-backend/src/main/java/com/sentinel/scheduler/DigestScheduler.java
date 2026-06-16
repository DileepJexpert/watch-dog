package com.sentinel.scheduler;

import com.sentinel.alerting.EmailNotifier;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.RemediationRepository;
import com.sentinel.repository.ServiceHealthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends a weekly health digest report every Monday at 9:00 AM UTC.
 * Summarizes incidents, remediations, and service health trends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestScheduler {

    private static final String DIGEST_RECIPIENT = "engineering-team@company.com";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    private final IncidentRepository incidentRepository;
    private final RemediationRepository remediationRepository;
    private final ServiceHealthRepository serviceHealthRepository;
    private final EmailNotifier emailNotifier;

    /** Every Monday at 09:00 UTC */
    @Scheduled(cron = "0 0 9 * * MON")
    public void sendWeeklyDigest() {
        log.info("Generating weekly health digest...");
        try {
            String report = buildWeeklyReport();
            emailNotifier.sendWeeklyDigest(report, DIGEST_RECIPIENT);
            log.info("Weekly health digest sent to {}", DIGEST_RECIPIENT);
        } catch (Exception e) {
            log.error("Failed to send weekly digest: {}", e.getMessage(), e);
        }
    }

    private String buildWeeklyReport() {
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<IncidentEntity> incidents = incidentRepository.findRecentIncidents(weekAgo);
        long remediations = remediationRepository.count();

        Map<String, Long> byService = incidents.stream()
                .collect(Collectors.groupingBy(IncidentEntity::getServiceName, Collectors.counting()));

        Map<String, Long> bySeverity = incidents.stream()
                .collect(Collectors.groupingBy(i -> i.getSeverity().name(), Collectors.counting()));

        long autoRemediated = incidents.stream().filter(IncidentEntity::isAutoRemediated).count();
        long openCount = incidents.stream()
                .filter(i -> i.getStatus() == IncidentStatus.OPEN).count();

        StringBuilder sb = new StringBuilder();
        sb.append("SENTINEL - Weekly Health Digest\n");
        sb.append("=================================\n");
        sb.append("Period: ").append(FORMATTER.format(weekAgo))
                .append(" to ").append(FORMATTER.format(Instant.now())).append("\n\n");

        sb.append("SUMMARY\n-------\n");
        sb.append("Total Incidents:    ").append(incidents.size()).append("\n");
        sb.append("Auto-Remediated:    ").append(autoRemediated).append("\n");
        sb.append("Still Open:         ").append(openCount).append("\n\n");

        sb.append("BY SEVERITY\n-----------\n");
        bySeverity.forEach((sev, count) -> sb.append(sev).append(": ").append(count).append("\n"));

        sb.append("\nTOP IMPACTED SERVICES\n---------------------\n");
        byService.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> sb.append(e.getKey()).append(": ").append(e.getValue()).append(" incidents\n"));

        sb.append("\nSERVICE HEALTH STATUS\n---------------------\n");
        serviceHealthRepository.findAll().forEach(health ->
                sb.append(health.getServiceName()).append(": ").append(health.getStatus()).append("\n"));

        return sb.toString();
    }
}
