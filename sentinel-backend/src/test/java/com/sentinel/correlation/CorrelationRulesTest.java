package com.sentinel.correlation;

import com.sentinel.correlation.rules.*;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.Severity;
import com.sentinel.model.enums.SignalType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationRulesTest {

    // ── MemoryLeakRule ────────────────────────────────────────────────────

    @Test
    void memoryLeakRule_triggersWhenAllThreeSignalsPresent() {
        MemoryLeakRule rule = new MemoryLeakRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofMetric("payment-service", Severity.P2_HIGH,
                        "High CPU", Map.of("high_cpu", true)),
                NormalizedEvent.ofLog("payment-service", Severity.P2_HIGH,
                        "OOM error", Map.of("oom_detected", true)),
                NormalizedEvent.ofTrace("payment-service", Severity.P2_HIGH,
                        "Timeout span", Map.of("timeout_spans", true))
        );

        Optional<IncidentEntity> incident = rule.evaluate(events, "payment-service");

        assertThat(incident).isPresent();
        assertThat(incident.get().getSeverity()).isEqualTo(Severity.P1_CRITICAL);
        assertThat(incident.get().getCorrelationRule()).isEqualTo("MEMORY_LEAK_RESOURCE_EXHAUSTION");
    }

    @Test
    void memoryLeakRule_doesNotTriggerWithMissingSignals() {
        MemoryLeakRule rule = new MemoryLeakRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofMetric("svc", Severity.P2_HIGH, "High CPU", Map.of("high_cpu", true)),
                NormalizedEvent.ofLog("svc", Severity.P2_HIGH, "Error", Map.of())
                // Missing OOM and timeout spans
        );

        assertThat(rule.evaluate(events, "svc")).isEmpty();
    }

    // ── CrashLoopRule ─────────────────────────────────────────────────────

    @Test
    void crashLoopRule_triggersOnPodRestartsAndCrashLoop() {
        CrashLoopRule rule = new CrashLoopRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofMetric("api-gw", Severity.P2_HIGH,
                        "Pod restarts", Map.of("crash_loop_suspected", true, "restart_count", 4.0)),
                NormalizedEvent.ofLog("api-gw", Severity.P2_HIGH,
                        "CrashLoopBackOff detected", Map.of())
        );

        Optional<IncidentEntity> incident = rule.evaluate(events, "api-gw");
        assertThat(incident).isPresent();
        assertThat(incident.get().getCorrelationRule()).isEqualTo("CRASH_LOOP_UNSTABLE_DEPLOYMENT");
    }

    // ── ServiceDownRule ───────────────────────────────────────────────────

    @Test
    void serviceDownRule_triggersOnUnreachableProbe() {
        ServiceDownRule rule = new ServiceDownRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofProbe("inventory-svc", Severity.P1_CRITICAL,
                        "UNREACHABLE", Map.of("service_down", true, "url", "http://inventory/health"))
        );

        Optional<IncidentEntity> incident = rule.evaluate(events, "inventory-svc");
        assertThat(incident).isPresent();
        assertThat(incident.get().getSeverity()).isEqualTo(Severity.P1_CRITICAL);
    }

    // ── HighErrorRateRule ────────────────────────────────────────────────

    @Test
    void highErrorRateRule_triggersAboveThreshold() {
        HighErrorRateRule rule = new HighErrorRateRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofMetric("checkout", Severity.P2_HIGH, "High error rate",
                        Map.of("metric_name", "error_rate_percent", "metric_value", 15.0,
                                "high_error_rate", true))
        );

        Optional<IncidentEntity> incident = rule.evaluate(events, "checkout");
        assertThat(incident).isPresent();
        assertThat(incident.get().getSeverity()).isEqualTo(Severity.P1_CRITICAL);
    }

    @Test
    void highErrorRateRule_doesNotTriggerBelowThreshold() {
        HighErrorRateRule rule = new HighErrorRateRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofMetric("checkout", Severity.P4_INFO, "Normal",
                        Map.of("metric_name", "error_rate_percent", "metric_value", 0.5))
        );

        assertThat(rule.evaluate(events, "checkout")).isEmpty();
    }

    // ── TlsExpiryRule ─────────────────────────────────────────────────────

    @Test
    void tlsExpiryRule_triggersWhenCertExpiresSoon() {
        TlsExpiryRule rule = new TlsExpiryRule();

        List<NormalizedEvent> events = List.of(
                NormalizedEvent.ofProbe("secure-api", Severity.P2_HIGH, "TLS cert expiring",
                        Map.of("tls_cert_expiring_soon", true, "tls_cert_expiry_days", 3L))
        );

        Optional<IncidentEntity> incident = rule.evaluate(events, "secure-api");
        assertThat(incident).isPresent();
        assertThat(incident.get().getTitle()).contains("3 days");
    }

    // ── SecurityAnomalyRule ───────────────────────────────────────────────

    @Test
    void securityAnomalyRule_triggersOnAuthFailureSpike() {
        SecurityAnomalyRule rule = new SecurityAnomalyRule();

        List<NormalizedEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            events.add(NormalizedEvent.ofLog("auth-service", Severity.P2_HIGH,
                    "authentication failed for user " + i, Map.of()));
        }

        Optional<IncidentEntity> incident = rule.evaluate(events, "auth-service");
        assertThat(incident).isPresent();
        assertThat(incident.get().getTitle()).contains("25 auth failures");
    }
}
