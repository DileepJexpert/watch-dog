package com.sentinel.agent.tools;

import com.sentinel.agent.AgentTool;
import com.sentinel.agent.dto.AgentEvidence;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists persisted incidents straight from Postgres.
 *
 * This fills a gap the other tools left: {@code correlate} only re-runs rules
 * against the live 5-minute sliding window (empty once events age out), and
 * {@code query_database} requires the model to hand-write SQL (which small
 * local models botch). When a user asks "what incidents are open?" or "show me
 * all incident detail", this is the reliable, zero-SQL answer.
 *
 * Reads the same IncidentRepository the dashboard's REST API uses, so the
 * agent and the dashboard always agree.
 */
@Component
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ListIncidentsTool implements AgentTool {

    private final IncidentRepository incidentRepository;

    @Override
    public String name() {
        return "list_incidents";
    }

    @Override
    public String description() {
        return "List incidents recorded by SENTINEL (from the database, not the live window). "
                + "Use status='OPEN' (default) for currently-active incidents, 'ALL' for everything "
                + "in the lookback window, or 'RESOLVED'. Optional 'service' filters to one service. "
                + "'sinceHours' bounds how far back to look when status=ALL (default 168 = 7 days). "
                + "This is the right tool for questions like 'what is broken now?' or "
                + "'show all incident detail'.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "string",
                                "enum", List.of("OPEN", "ALL", "RESOLVED", "INVESTIGATING"),
                                "description", "which incidents to return (default OPEN)"),
                        "service", Map.of("type", "string",
                                "description", "exact service name to filter on (optional)"),
                        "sinceHours", Map.of("type", "integer", "minimum", 1, "maximum", 720,
                                "description", "lookback window in hours when status=ALL (default 168)"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                                "description", "max incidents to return (default 50)")));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String status = strArg(args, "status", "OPEN").toUpperCase();
        String service = strArg(args, "service", "");
        int sinceHours = intArg(args, "sinceHours", 168);
        int limit = Math.min(intArg(args, "limit", 50), 100);

        try {
            List<IncidentEntity> incidents = fetch(status, sinceHours);

            // Optional service filter (post-query — incident counts are small).
            if (!service.isBlank()) {
                incidents = incidents.stream()
                        .filter(i -> service.equalsIgnoreCase(i.getServiceName()))
                        .toList();
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (IncidentEntity i : incidents) {
                if (rows.size() >= limit) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", i.getId() == null ? "" : i.getId().toString());
                row.put("service", i.getServiceName());
                row.put("title", i.getTitle());
                row.put("severity", i.getSeverity() == null ? "" : i.getSeverity().name());
                row.put("status", i.getStatus() == null ? "" : i.getStatus().name());
                row.put("rule", i.getCorrelationRule());
                row.put("detectedAt", i.getDetectedAt() == null ? "" : i.getDetectedAt().toString());
                row.put("autoRemediated", i.isAutoRemediated());
                rows.add(row);

                evidence.add(new AgentEvidence(
                        "incident",
                        "id=" + (i.getId() == null ? "?" : i.getId()),
                        String.format("[%s] %s — %s (rule=%s)",
                                i.getSeverity(), i.getServiceName(), i.getTitle(),
                                i.getCorrelationRule())));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", status);
            payload.put("count", rows.size());
            payload.put("incidents", rows);
            return ToolResult.ok(payload, evidence);
        } catch (Exception e) {
            return ToolResult.error("list_incidents failed: " + e.getMessage());
        }
    }

    private List<IncidentEntity> fetch(String status, int sinceHours) {
        return switch (status) {
            case "RESOLVED" ->
                    incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.RESOLVED);
            case "INVESTIGATING" ->
                    incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.INVESTIGATING);
            case "ALL" ->
                    incidentRepository.findRecentIncidents(
                            Instant.now().minus(Math.min(sinceHours, 720), ChronoUnit.HOURS));
            default -> // OPEN
                    incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.OPEN);
        };
    }

    private String strArg(Map<String, Object> args, String key, String fallback) {
        if (args == null) return fallback;
        Object v = args.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private int intArg(Map<String, Object> args, String key, int fallback) {
        if (args == null) return fallback;
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? fallback : Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
