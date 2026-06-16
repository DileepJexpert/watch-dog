package com.sentinel.agent.tools;

import com.sentinel.agent.AgentTool;
import com.sentinel.agent.dto.AgentEvidence;
import com.sentinel.ingestion.RuntimeMetricsConnector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GetRuntimeMetricsTool implements AgentTool {

    private final RuntimeMetricsConnector connector;

    @Override
    public String name() {
        return "get_runtime_metrics";
    }

    @Override
    public String description() {
        return "Fetch live runtime signals from a target service: CPU, memory, thread pool size, executor queue depth, JVM heap. Pulls from Spring Boot Actuator / Micrometer by default. Pass `serviceUrl` (base URL) or rely on the central APM_URL.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "serviceUrl", Map.of("type", "string",
                                "description", "base URL of the target service (omit to use central APM_URL)")));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String url = args == null ? null : (String) args.get("serviceUrl");
        try {
            Map<String, Object> payload = connector.fetchRuntimeMetrics(url);
            List<AgentEvidence> evidence = new ArrayList<>();
            evidence.add(new AgentEvidence("runtime",
                    "source=" + payload.getOrDefault("source", "unknown"),
                    "snapshot of CPU/mem/threads/queue"));
            return ToolResult.ok(payload, evidence);
        } catch (Exception e) {
            return ToolResult.error("get_runtime_metrics failed: " + e.getMessage());
        }
    }
}
