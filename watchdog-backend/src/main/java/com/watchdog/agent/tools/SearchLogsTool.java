package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.ingestion.ElasticsearchConnector;
import com.watchdog.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent tool wrapping the existing ElasticsearchConnector — pulls recent error / warn
 * log entries (optionally filtered by service) so the model can reason over them.
 */
@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SearchLogsTool implements AgentTool {

    private final ElasticsearchConnector elasticsearchConnector;

    @Override
    public String name() {
        return "search_logs";
    }

    @Override
    public String description() {
        return "Search recent application logs (Kibana / Elasticsearch). Returns ERROR/WARN entries from the last poll window. Optional 'service' filter narrows to one service.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "service name to filter on"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200)));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? null : (String) args.get("service");
        int limit = parseInt(args, "limit", 50);
        try {
            List<NormalizedEvent> all = elasticsearchConnector.fetchRecentErrorLogs();
            List<Map<String, Object>> rows = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (NormalizedEvent e : all) {
                if (service != null && !service.isBlank()
                        && !service.equalsIgnoreCase(e.serviceName())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("timestamp", e.timestamp().toString());
                row.put("service", e.serviceName());
                row.put("severity", e.severity().name());
                row.put("message", e.message());
                if (e.attributes() != null) {
                    row.put("level", e.attributes().getOrDefault("log_level", ""));
                    row.put("error_type", e.attributes().getOrDefault("error_type", ""));
                }
                rows.add(row);
                String esId = e.attributes() == null ? "" : String.valueOf(e.attributes().getOrDefault("es_id", ""));
                evidence.add(new AgentEvidence("logs", "es_id=" + esId, truncate(e.message(), 240)));
                if (rows.size() >= limit) break;
            }
            return ToolResult.ok(Map.of("count", rows.size(), "entries", rows), evidence);
        } catch (Exception e) {
            return ToolResult.error("search_logs failed: " + e.getMessage());
        }
    }

    private int parseInt(Map<String, Object> args, String key, int fallback) {
        if (args == null) return fallback;
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? fallback : Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
