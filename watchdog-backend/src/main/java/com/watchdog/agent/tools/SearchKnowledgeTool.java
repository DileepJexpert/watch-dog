package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.knowledge.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SearchKnowledgeTool implements AgentTool {

    private final KnowledgeService knowledgeService;

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "Search the WATCHDOG knowledge base (ingested runbooks + resolved past incidents) for documents relevant to the current investigation. Returns top-k matches with similarity scores.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "topK", Map.of("type", "integer", "minimum", 1, "maximum", 20)),
                "required", List.of("query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = args == null ? "" : String.valueOf(args.getOrDefault("query", ""));
        int topK = args != null && args.get("topK") instanceof Number n ? n.intValue() : 0;
        if (query.isBlank()) return ToolResult.error("query is required");

        try {
            List<KnowledgeService.RetrievalResult> results = knowledgeService.search(query, topK);
            List<Map<String, Object>> rows = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (KnowledgeService.RetrievalResult r : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.doc().getId());
                row.put("sourceType", r.doc().getSourceType());
                row.put("title", r.doc().getTitle());
                row.put("content", truncate(r.doc().getContent(), 1200));
                row.put("score", r.score());
                rows.add(row);
                evidence.add(new AgentEvidence("knowledge",
                        "doc_id=" + r.doc().getId(),
                        r.doc().getTitle() + " (score=" + String.format(Locale.ROOT, "%.3f", r.score()) + ")"));
            }
            return ToolResult.ok(Map.of("count", rows.size(), "matches", rows), evidence);
        } catch (Exception e) {
            return ToolResult.error("search_knowledge failed: " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
