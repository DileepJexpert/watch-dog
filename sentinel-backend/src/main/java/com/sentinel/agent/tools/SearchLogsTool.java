package com.sentinel.agent.tools;

import com.sentinel.agent.AgentTool;
import com.sentinel.agent.dto.AgentEvidence;
import com.sentinel.ingestion.ElasticsearchConnector;
import com.sentinel.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent tool wrapping the existing ElasticsearchConnector — pulls recent error / warn
 * log entries (optionally filtered by service) so the model can reason over them.
 */
@Component
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SearchLogsTool implements AgentTool {

    private final ElasticsearchConnector elasticsearchConnector;

    @Override
    public String name() {
        return "search_logs";
    }

    @Override
    public String description() {
        return "Search application logs (Kibana / Elasticsearch) for ERROR/WARN/FATAL entries. "
                + "Use 'sinceMinutes' to control how far back to look (default 60; use 1440 for "
                + "the last day, 10080 for the last week). Optional 'service' filter narrows to "
                + "one service by exact name. Returns matching log lines newest-first.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "exact service name to filter on (optional)"),
                        "sinceMinutes", Map.of("type", "integer", "minimum", 1, "maximum", 10080,
                                "description", "how many minutes back to search (default 60)"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                                "description", "max log lines to return (default 50)")));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? null : (String) args.get("service");
        int limit = parseInt(args, "limit", 50);
        // Default to a 60-minute lookback — the ingestion poller's ~35s window
        // is far too narrow for an interactive question like "what errors
        // happened today?". The model can widen it via sinceMinutes.
        int sinceMinutes = parseInt(args, "sinceMinutes", 60);
        try {
            List<NormalizedEvent> all =
                    elasticsearchConnector.searchErrorLogs(sinceMinutes, service, Math.max(limit, 50));
            List<Map<String, Object>> rows = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (NormalizedEvent e : all) {
                // Service filter is applied server-side in searchErrorLogs now.
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
