package com.sentinel.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.agent.dto.AgentAnswer;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FR-7 LLM-assisted root cause analysis.
 *
 * Augments (does not replace) the rule-based correlation: when a correlation
 * rule fires and {@code sentinel.agent.rca-on-correlation=true}, the rule
 * engine fires an RCA pass through the agent loop which then assembles
 * signals + knowledge into a hypothesis + confidence + cited evidence.
 *
 * The output is broadcast on /topic/incidents/rca/{incidentId} for the UI;
 * the agent_audit row captures the full run for auditability.
 */
@Slf4j
@Service
@ConditionalOnBean(AgentOrchestrator.class)
@RequiredArgsConstructor
public class RcaService {

    private final AgentOrchestrator orchestrator;
    private final SentinelProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async("rcaExecutor")
    public void analyze(IncidentEntity incident) {
        if (!properties.getAgent().isRcaOnCorrelation()) return;
        try {
            String question = buildQuestion(incident);
            AgentAnswer answer = orchestrator.ask(
                    "rca-" + UUID.randomUUID(), question, null);
            log.info("RCA hypothesis for incident {}: {} (confidence: {})",
                    incident.getId(),
                    answer.rootCause() == null ? "n/a" : answer.rootCause().hypothesis(),
                    answer.rootCause() == null ? "n/a" : answer.rootCause().confidence());
        } catch (Exception e) {
            log.warn("RCA pass failed for incident {}: {}", incident.getId(), e.getMessage());
        }
    }

    private String buildQuestion(IncidentEntity incident) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("incidentId", incident.getId().toString());
        context.put("service", incident.getServiceName());
        context.put("title", incident.getTitle());
        context.put("severity", incident.getSeverity().name());
        context.put("correlationRule", incident.getCorrelationRule());
        context.put("correlatedSignals", incident.getCorrelatedSignalIds());

        String contextJson;
        try {
            contextJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (JsonProcessingException e) {
            contextJson = context.toString();
        }
        return """
                A correlation rule has fired for an active incident. Investigate
                the root cause using the available tools, then call finalize_answer.

                Incident context:
                %s

                Required: identify the most likely root cause with confidence,
                cite the specific log/trace/metric/knowledge records that support
                it, and recommend remediation steps. You may NOT execute any
                remediation — recommendations only (requiresApproval=true).
                """.formatted(contextJson);
    }
}
