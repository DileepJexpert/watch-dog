package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.ingestion.DatabaseConnector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * FR-5 query_database tool — agent's read-only window into target app DBs.
 * SELECT-only. Row + timeout caps come from each target's configuration.
 */
@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class QueryDatabaseTool implements AgentTool {

    private final DatabaseConnector connector;

    @Override
    public String name() {
        return "query_database";
    }

    @Override
    public String description() {
        return "Run a read-only SELECT against one of the configured application databases. SELECT only — writes and DDL are rejected. Returns columns + rows. List target names first if unsure.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "target", Map.of("type", "string",
                                "description", "configured target name (e.g. payments-readonly)"),
                        "sql", Map.of("type", "string",
                                "description", "SELECT statement; do not append a semicolon"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 500)),
                "required", List.of("target", "sql"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (args == null) return ToolResult.error("missing arguments");
        String target = String.valueOf(args.getOrDefault("target", ""));
        String sql = String.valueOf(args.getOrDefault("sql", ""));
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

        if (target.isBlank()) {
            return ToolResult.ok(Map.of(
                    "availableTargets", connector.targetNames(),
                    "message", "no target specified; choose one of availableTargets and retry"),
                    List.of());
        }
        try {
            DatabaseConnector.QueryResult result = connector.query(target, sql, limit);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("target", target);
            payload.put("columns", result.columns());
            payload.put("rowCount", result.rows().size());
            payload.put("truncated", result.truncated());
            payload.put("rows", result.rows());

            List<AgentEvidence> evidence = new ArrayList<>();
            evidence.add(new AgentEvidence("db",
                    "target=" + target + "; rows=" + result.rows().size(),
                    sqlExcerpt(sql)));
            return ToolResult.ok(payload, evidence);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("query_database failed: " + e.getMessage());
        }
    }

    private String sqlExcerpt(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
