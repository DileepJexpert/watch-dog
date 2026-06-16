package com.sentinel.alerting;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertingServiceTest {

    @Mock
    private SlackNotifier slackNotifier;
    @Mock
    private EmailNotifier emailNotifier;
    @Mock
    private PagerDutyNotifier pagerDutyNotifier;
    @Mock
    private OpsGenieNotifier opsGenieNotifier;

    @InjectMocks
    private AlertingService alertingService;

    @Test
    void alert_p1Critical_triggersAllChannels() {
        IncidentEntity incident = buildIncident(Severity.P1_CRITICAL);

        alertingService.alert(incident);

        verify(slackNotifier).sendCritical(incident);
        verify(emailNotifier).sendAlert(incident);
        verify(pagerDutyNotifier).triggerIncident(incident);
        verify(opsGenieNotifier).createAlert(incident);
    }

    @Test
    void alert_p2High_triggersSlackAndEmail() {
        IncidentEntity incident = buildIncident(Severity.P2_HIGH);

        alertingService.alert(incident);

        verify(slackNotifier).sendHigh(incident);
        verify(emailNotifier).sendAlert(incident);
        verifyNoInteractions(pagerDutyNotifier, opsGenieNotifier);
    }

    @Test
    void alert_p3Medium_triggersSlackOnly() {
        IncidentEntity incident = buildIncident(Severity.P3_MEDIUM);

        alertingService.alert(incident);

        verify(slackNotifier).sendMedium(incident);
        verifyNoInteractions(emailNotifier, pagerDutyNotifier, opsGenieNotifier);
    }

    @Test
    void alert_p4Info_triggersNothing() {
        IncidentEntity incident = buildIncident(Severity.P4_INFO);

        alertingService.alert(incident);

        verifyNoInteractions(slackNotifier, emailNotifier, pagerDutyNotifier, opsGenieNotifier);
    }

    private IncidentEntity buildIncident(Severity severity) {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(UUID.randomUUID());
        incident.setServiceName("test-service");
        incident.setTitle("Test Incident");
        incident.setSeverity(severity);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setCorrelationRule("TEST_RULE");
        return incident;
    }
}
