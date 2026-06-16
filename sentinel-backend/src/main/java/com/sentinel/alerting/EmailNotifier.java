package com.sentinel.alerting;

import com.sentinel.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends incident alert emails via Spring Mail (SMTP).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotifier {

    private final JavaMailSender mailSender;

    public void sendAlert(IncidentEntity incident) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo("oncall@company.com");
            message.setSubject(String.format("[SENTINEL] %s - %s",
                    incident.getSeverity().getLabel(), incident.getTitle()));
            message.setText(buildEmailBody(incident));
            mailSender.send(message);
            log.debug("Email alert sent for incident {}", incident.getId());
        } catch (Exception e) {
            log.warn("Failed to send email alert: {}", e.getMessage());
        }
    }

    public void sendWeeklyDigest(String reportHtml, String recipientEmail) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(recipientEmail);
            message.setSubject("[SENTINEL] Weekly Health Digest");
            message.setText(reportHtml);
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Failed to send weekly digest: {}", e.getMessage());
        }
    }

    private String buildEmailBody(IncidentEntity incident) {
        return String.format("""
                SENTINEL Incident Alert
                =======================
                Severity:  %s
                Service:   %s
                Title:     %s
                Rule:      %s
                Detected:  %s
                Status:    %s
                Auto-Remediated: %s

                Incident ID: %s

                Review in SENTINEL Dashboard: http://sentinel/incidents/%s
                """,
                incident.getSeverity().getLabel(),
                incident.getServiceName(),
                incident.getTitle(),
                incident.getCorrelationRule(),
                incident.getDetectedAt(),
                incident.getStatus(),
                incident.isAutoRemediated() ? "Yes" : "No",
                incident.getId(),
                incident.getId());
    }
}
