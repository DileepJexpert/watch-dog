package com.sentinel.scheduler;

import com.sentinel.agent.AgentOrchestrator;
import com.sentinel.agent.dto.AgentAnswer;
import com.sentinel.config.SentinelProperties;
import com.sentinel.correlation.SlidingWindowBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Phase-3 proactive scan. When enabled, the scheduler periodically asks the
 * agent to look across every active service for emerging risks the rules
 * haven't yet flagged. Output is pushed to /topic/agent/proactive for the UI
 * and recorded in agent_audit.
 *
 * Disabled by default (interval=0). Set
 *   AGENT_PROACTIVE_SCAN_SECONDS=300
 * to enable.
 */
@Slf4j
@Component
// Property condition, not @ConditionalOnBean — see AgentOrchestrator for why.
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProactiveScanScheduler {

    private final AgentOrchestrator orchestrator;
    private final SlidingWindowBuffer windowBuffer;
    private final SimpMessagingTemplate messagingTemplate;
    private final SentinelProperties properties;

    // fixedDelay must be > 0 or Spring fails context startup. When the configured
    // interval is 0 (proactive scan disabled) we still need a positive cadence, so
    // we tick hourly and no-op inside scan(); when enabled we use the real interval.
    @Scheduled(fixedDelayString = "#{${sentinel.agent.proactive-scan-interval-seconds:0} <= 0 ? 3600000 : ${sentinel.agent.proactive-scan-interval-seconds:0} * 1000}")
    public void scan() {
        int interval = properties.getAgent().getProactiveScanIntervalSeconds();
        if (interval <= 0) return;

        Set<String> services = windowBuffer.getActiveServices();
        if (services.isEmpty()) return;

        String question = """
                Run a proactive health sweep across the active services: %s.
                For each service, decide whether there is an emerging risk (rising
                error rate, latency drift, anomalous metric, slow queries) the
                rule-based correlation has not yet flagged. Use tools to gather
                evidence, then finalize_answer with the single highest-priority
                risk you would surface to on-call. If everything looks healthy,
                say so and confidence='low'.
                """.formatted(services);

        try {
            AgentAnswer answer = orchestrator.ask("proactive-" + UUID.randomUUID(), question, null);
            messagingTemplate.convertAndSend("/topic/agent/proactive", answer);
            log.info("Proactive scan completed; summary: {}",
                    answer.summary() == null ? "" : answer.summary().substring(0, Math.min(120, answer.summary().length())));
        } catch (Exception e) {
            log.warn("Proactive scan failed: {}", e.getMessage());
        }
    }
}
