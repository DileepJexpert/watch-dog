package com.sentinel.scheduler;

import com.sentinel.aihub.LlmClient;
import com.sentinel.aihub.model.LlmResponse;
import com.sentinel.aihub.model.Usage;
import com.sentinel.alerting.EmailNotifier;
import com.sentinel.alerting.SlackNotifier;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.entity.ServiceHealthEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.ServiceStatus;
import com.sentinel.model.enums.Severity;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.RemediationRepository;
import com.sentinel.repository.ServiceHealthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyAiHealthReportSchedulerTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private RemediationRepository remediationRepository;
    @Mock private ServiceHealthRepository serviceHealthRepository;
    @Mock private SlackNotifier slackNotifier;
    @Mock private EmailNotifier emailNotifier;
    @Mock private LlmClient llmClient;
    @Mock private ObjectProvider<LlmClient> llmClientProvider;

    private SentinelProperties properties;
    private DailyAiHealthReportScheduler scheduler;

    @BeforeEach
    void setup() {
        properties = new SentinelProperties();
        properties.getDigest().getDaily().setEnabled(true);
        properties.getDigest().getDaily().setSlack(true);
        properties.getDigest().getDaily().setEmailRecipients(List.of("oncall@example.com"));

        when(incidentRepository.findRecentIncidents(any())).thenReturn(sampleIncidents());
        when(remediationRepository.count()).thenReturn(42L);
        when(serviceHealthRepository.findAll()).thenReturn(sampleHealth());

        scheduler = new DailyAiHealthReportScheduler(
                incidentRepository, remediationRepository, serviceHealthRepository,
                slackNotifier, emailNotifier, properties, llmClientProvider);
    }

    @Test
    void generateReport_includesCountsSeverityAndTopServices_whenAiUnavailable() {
        when(llmClientProvider.getIfAvailable()).thenReturn(null);

        String report = scheduler.generateReport();

        assertThat(report)
                .contains("SENTINEL DAILY HEALTH DIGEST")
                .contains("Total incidents:        3")
                .contains("Still open:             2")
                .contains("Auto-remediated:        1")
                .contains("payments-svc")
                .contains("DB_CONNECTIVITY")
                .doesNotContain("EXECUTIVE SUMMARY (AI)"); // no AI section when LLM absent
    }

    @Test
    void generateReport_includesAiSummary_whenLlmClientPresent() {
        properties.getDigest().getDaily().setUseAi(true);
        when(llmClientProvider.getIfAvailable()).thenReturn(llmClient);
        when(llmClient.chat(anyList(), anyList(), any()))
                .thenReturn(new LlmResponse(
                        "Payments backend saw a spike in DB connectivity errors. Recommend investigating Hikari pool.",
                        List.of(), new Usage(120, 60), "end_turn"));

        String report = scheduler.generateReport();

        assertThat(report)
                .contains("EXECUTIVE SUMMARY (AI)")
                .contains("Payments backend saw a spike in DB connectivity errors")
                .contains("BY THE NUMBERS");
    }

    @Test
    void generateReport_fallsBackToTemplate_whenLlmClientThrows() {
        when(llmClientProvider.getIfAvailable()).thenReturn(llmClient);
        when(llmClient.chat(anyList(), anyList(), any()))
                .thenThrow(new RuntimeException("model timed out"));

        String report = scheduler.generateReport();

        // No AI section because the LLM call failed — but the rest of the
        // report is still produced and shippable.
        assertThat(report)
                .doesNotContain("EXECUTIVE SUMMARY (AI)")
                .contains("Total incidents:        3");
    }

    @Test
    void runAndSend_dispatchesViaSlackAndEmail() {
        when(llmClientProvider.getIfAvailable()).thenReturn(null);

        scheduler.runAndSend();

        verify(slackNotifier, times(1)).sendHealthDigest(anyString(), anyString());
        verify(emailNotifier, times(1))
                .sendDailyDigest(anyString(), anyString(), any());
    }

    @Test
    void runAndSend_skipsSlack_whenSlackDisabledInConfig() {
        properties.getDigest().getDaily().setSlack(false);
        when(llmClientProvider.getIfAvailable()).thenReturn(null);

        scheduler.runAndSend();

        verify(slackNotifier, never()).sendHealthDigest(anyString(), anyString());
        verify(emailNotifier).sendDailyDigest(anyString(), anyString(), any());
    }

    private List<IncidentEntity> sampleIncidents() {
        IncidentEntity a = incident("payments-svc", Severity.P1_CRITICAL,
                IncidentStatus.OPEN, "DB_CONNECTIVITY", true);
        IncidentEntity b = incident("payments-svc", Severity.P2_HIGH,
                IncidentStatus.OPEN, "HIGH_ERROR_RATE", false);
        IncidentEntity c = incident("auth-svc", Severity.P2_HIGH,
                IncidentStatus.RESOLVED, "LATENCY_DEGRADATION", false);
        return List.of(a, b, c);
    }

    private IncidentEntity incident(String service, Severity sev, IncidentStatus status,
                                    String rule, boolean autoRemediated) {
        IncidentEntity e = new IncidentEntity();
        e.setId(UUID.randomUUID());
        e.setServiceName(service);
        e.setTitle(rule + " on " + service);
        e.setSeverity(sev);
        e.setStatus(status);
        e.setCorrelationRule(rule);
        e.setAutoRemediated(autoRemediated);
        e.setDetectedAt(Instant.now());
        return e;
    }

    private List<ServiceHealthEntity> sampleHealth() {
        ServiceHealthEntity h1 = new ServiceHealthEntity();
        h1.setServiceName("payments-svc");
        h1.setStatus(ServiceStatus.YELLOW);  // degraded
        ServiceHealthEntity h2 = new ServiceHealthEntity();
        h2.setServiceName("auth-svc");
        h2.setStatus(ServiceStatus.GREEN);  // healthy
        return List.of(h1, h2);
    }
}
