package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.ingestion.JaegerConnector;
import com.watchdog.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GetTracesTool implements AgentTool {

    private final JaegerConnector jaegerConnector;

    @Override
    public String name() {
        return "get_traces";
    }

    @Override
    public String description() {
        return "Fetch recent slow / error distributed traces from Jaeger. Optional 'service' filter narrows to one service.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "service name to filter on"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? null : (String) args.get("service");
        int limit = parseInt(args, "limit", 25);
        try {
            List<NormalizedEvent> traces = jaegerConnector.fetchSlowAndErrorTraces();
            List<Map<String, Object>> rows = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (NormalizedEvent e : traces) {
                if (service != null && !service.isBlank()
                        && !service.equalsIgnoreCase(e.serviceName())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", e.serviceName());
                row.put("severity", e.severity().name());
                row.put("message", e.message());
                Map<String, Object> attrs = e.attributes() == null ? Map.of() : e.attributes();
                row.put("trace_id", attrs.getOrDefault("trace_id", ""));
                row.put("duration_ms", attrs.getOrDefault("duration_ms", 0));
                row.put("error_span_count", attrs.getOrDefault("error_span_count", 0));
                row.put("root_operation", attrs.getOrDefault("root_operation", ""));
                row.put("cascading_failure", attrs.getOrDefault("cascading_failure", false));
                rows.add(row);
                evidence.add(new AgentEvidence("traces",
                        "trace_id=" + attrs.getOrDefault("trace_id", "?"),
                        e.message()));
                if (rows.size() >= limit) break;
            }
            return ToolResult.ok(Map.of("count", rows.size(), "traces", rows), evidence);
        } catch (Exception e) {
            return ToolResult.error("get_traces failed: " + e.getMessage());
        }
    }

    private int parseInt(Map<String, Object> args, String key, int fallback) {
        if (args == null) return fallback;
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? fallback : Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
