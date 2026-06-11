package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.ingestion.GrafanaConnector;
import com.watchdog.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class QueryMetricsTool implements AgentTool {

    private final GrafanaConnector grafanaConnector;

    @Override
    public String name() {
        return "query_metrics";
    }

    @Override
    public String description() {
        return "Fetch current Prometheus-backed metrics (CPU, memory, error rate, latency P95/P99, pod restarts) via Grafana. Optional 'query' runs a raw PromQL expression.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "service name filter"),
                        "query", Map.of("type", "string", "description", "optional raw PromQL expression")));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? null : (String) args.get("service");
        String query = args == null ? null : (String) args.get("query");
        try {
            List<AgentEvidence> evidence = new ArrayList<>();

            if (query != null && !query.isBlank()) {
                Map<String, Double> values = grafanaConnector.fetchCurrentMetricValues(query);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Map.Entry<String, Double> e : values.entrySet()) {
                    if (service != null && !service.isBlank()
                            && !service.equalsIgnoreCase(e.getKey())) continue;
                    rows.add(Map.of("service", e.getKey(), "value", e.getValue()));
                    evidence.add(new AgentEvidence("metrics",
                            "promql=" + query + "; service=" + e.getKey(),
                            "value=" + e.getValue()));
                }
                return ToolResult.ok(Map.of("query", query, "results", rows), evidence);
            }

            List<NormalizedEvent> events = grafanaConnector.fetchMetrics();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NormalizedEvent e : events) {
                if (service != null && !service.isBlank()
                        && !service.equalsIgnoreCase(e.serviceName())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", e.serviceName());
                row.put("severity", e.severity().name());
                row.put("message", e.message());
                row.putAll(e.attributes() == null ? Map.of() : e.attributes());
                rows.add(row);
                evidence.add(new AgentEvidence("metrics",
                        "service=" + e.serviceName(),
                        e.message()));
            }
            return ToolResult.ok(Map.of("count", rows.size(), "metrics", rows), evidence);
        } catch (Exception e) {
            return ToolResult.error("query_metrics failed: " + e.getMessage());
        }
    }
}
