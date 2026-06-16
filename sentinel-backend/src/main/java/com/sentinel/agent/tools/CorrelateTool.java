package com.sentinel.agent.tools;

import com.sentinel.agent.AgentTool;
import com.sentinel.agent.dto.AgentEvidence;
import com.sentinel.correlation.SlidingWindowBuffer;
import com.sentinel.correlation.rules.CorrelationRule;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Wraps the existing rule-based correlation engine so the agent can ask
 * "what rule-defined incident is this service currently in?" without
 * waiting for the 30s scheduler tick.
 */
@Component
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CorrelateTool implements AgentTool {

    private final SlidingWindowBuffer windowBuffer;
    private final List<CorrelationRule> rules;

    @Override
    public String name() {
        return "correlate";
    }

    @Override
    public String description() {
        return "Evaluate the 20 SENTINEL correlation rules across the current sliding window for a service. Returns the rule names that match and the synthesized incident proposals.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string")),
                "required", List.of("service"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? "" : String.valueOf(args.getOrDefault("service", ""));
        if (service.isBlank()) return ToolResult.error("service is required");

        try {
            List<NormalizedEvent> window = windowBuffer.getWindow(service);
            List<Map<String, Object>> matches = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (CorrelationRule rule : rules) {
                try {
                    Optional<IncidentEntity> incident = rule.evaluate(window, service);
                    if (incident.isPresent()) {
                        IncidentEntity inc = incident.get();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("rule", rule.getName());
                        row.put("title", inc.getTitle());
                        row.put("severity", inc.getSeverity().name());
                        row.put("signals", inc.getCorrelatedSignalIds());
                        matches.add(row);
                        evidence.add(new AgentEvidence("correlation",
                                "rule=" + rule.getName() + "; service=" + service,
                                inc.getTitle()));
                    }
                } catch (Exception ignored) { /* per-rule failures shouldn't kill the tool */ }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("service", service);
            payload.put("windowEvents", window.size());
            payload.put("matchedRules", matches);
            return ToolResult.ok(payload, evidence);
        } catch (Exception e) {
            return ToolResult.error("correlate failed: " + e.getMessage());
        }
    }
}
