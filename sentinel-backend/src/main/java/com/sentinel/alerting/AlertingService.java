package com.sentinel.alerting;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Routes incidents to appropriate notification channels based on severity tier.
 *
 * P1 Critical:  Slack + PagerDuty (phone call) + Email
 * P2 High:      Slack + Email
 * P3 Medium:    Slack channel only
 * P4 Info:      Dashboard + weekly digest only (no immediate notification)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertingService {

    private final SlackNotifier slackNotifier;
    private final EmailNotifier emailNotifier;
    private final PagerDutyNotifier pagerDutyNotifier;
    private final OpsGenieNotifier opsGenieNotifier;
    private final TeamsNotifier teamsNotifier;

    public void alert(IncidentEntity incident) {
        Severity severity = incident.getSeverity();
        log.info("Sending alert for incident {} severity {} service {}",
                incident.getId(), severity, incident.getServiceName());

        switch (severity) {
            case P1_CRITICAL -> alertP1(incident);
            case P2_HIGH -> alertP2(incident);
            case P3_MEDIUM -> alertP3(incident);
            case P4_INFO -> {
                // Dashboard only - no push notification
                log.debug("P4 incident {} - dashboard only", incident.getId());
            }
        }
    }

    private void alertP1(IncidentEntity incident) {
        slackNotifier.sendCritical(incident);
        teamsNotifier.sendCritical(incident);
        emailNotifier.sendAlert(incident);
        pagerDutyNotifier.triggerIncident(incident);
        // OpsGenie as backup paging
        opsGenieNotifier.createAlert(incident);
    }

    private void alertP2(IncidentEntity incident) {
        slackNotifier.sendHigh(incident);
        teamsNotifier.sendHigh(incident);
        emailNotifier.sendAlert(incident);
    }

    private void alertP3(IncidentEntity incident) {
        slackNotifier.sendMedium(incident);
        teamsNotifier.sendMedium(incident);
    }

    /**
     * Sends a resolution notification when an incident is resolved.
     */
    public void alertResolved(IncidentEntity incident) {
        slackNotifier.sendResolved(incident);
        teamsNotifier.sendResolved(incident);
        if (incident.getSeverity() == Severity.P1_CRITICAL) {
            pagerDutyNotifier.resolveIncident(incident);
            opsGenieNotifier.closeAlert(incident);
        }
    }
}
