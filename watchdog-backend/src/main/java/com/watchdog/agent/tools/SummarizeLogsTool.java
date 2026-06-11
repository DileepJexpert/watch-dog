package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.ingestion.ElasticsearchConnector;
import com.watchdog.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FR-6 intelligent log analysis. Instead of dumping raw log lines back to the
 * LLM (cost + noise), this tool computes:
 *   - exception/signature buckets via signature extraction
 *   - representative example lines per bucket
 *   - top services & error types
 *
 * The model then has a compact summary to reason over and cite specific lines
 * as evidence.
 */
@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SummarizeLogsTool implements AgentTool {

    private static final Pattern EXCEPTION = Pattern.compile("([A-Za-z0-9_$.]+(?:Exception|Error))");
    private static final Pattern NUMERIC = Pattern.compile("\\b\\d+\\b");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+");

    private final ElasticsearchConnector elasticsearchConnector;

    @Override
    public String name() {
        return "summarize_logs";
    }

    @Override
    public String description() {
        return "Summarize a recent burst of error/warn logs into dominant failure patterns. Buckets log lines by signature, returns counts per bucket, top exceptions, and 2-3 example lines per bucket. Use this before reading raw logs to avoid noise.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string"),
                        "maxBuckets", Map.of("type", "integer", "minimum", 1, "maximum", 20)));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? null : (String) args.get("service");
        int maxBuckets = args != null && args.get("maxBuckets") instanceof Number n ? n.intValue() : 5;

        try {
            List<NormalizedEvent> logs = elasticsearchConnector.fetchRecentErrorLogs();
            Map<String, Bucket> buckets = new HashMap<>();
            Map<String, Integer> exceptionCounts = new HashMap<>();
            Map<String, Integer> serviceCounts = new HashMap<>();
            int considered = 0;

            for (NormalizedEvent e : logs) {
                if (service != null && !service.isBlank()
                        && !service.equalsIgnoreCase(e.serviceName())) continue;
                considered++;
                String signature = signatureOf(e.message());
                buckets.computeIfAbsent(signature, k -> new Bucket(signature)).add(e);
                serviceCounts.merge(e.serviceName(), 1, Integer::sum);

                Matcher m = EXCEPTION.matcher(e.message() == null ? "" : e.message());
                while (m.find()) exceptionCounts.merge(m.group(1), 1, Integer::sum);
            }

            List<Bucket> top = new ArrayList<>(buckets.values());
            top.sort(Comparator.comparingInt((Bucket b) -> b.count).reversed());
            if (top.size() > maxBuckets) top = top.subList(0, maxBuckets);

            List<Map<String, Object>> bucketRows = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (Bucket b : top) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("signature", b.signature);
                row.put("count", b.count);
                row.put("examples", b.examples);
                bucketRows.add(row);
                evidence.add(new AgentEvidence("logs",
                        "signature=" + truncate(b.signature, 80),
                        "count=" + b.count + ", e.g. " + (b.examples.isEmpty() ? "" : b.examples.get(0))));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("considered", considered);
            payload.put("buckets", bucketRows);
            payload.put("topExceptions", topN(exceptionCounts, 5));
            payload.put("topServices", topN(serviceCounts, 5));
            return ToolResult.ok(payload, evidence);
        } catch (Exception e) {
            return ToolResult.error("summarize_logs failed: " + e.getMessage());
        }
    }

    /** Collapse a log line to a signature by removing variable tokens. */
    private String signatureOf(String message) {
        if (message == null) return "(empty)";
        String s = message;
        s = UUID.matcher(s).replaceAll("<UUID>");
        s = HEX.matcher(s).replaceAll("<HEX>");
        s = NUMERIC.matcher(s).replaceAll("<N>");
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private List<Map<String, Object>> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("key", e.getKey());
                    r.put("count", e.getValue());
                    return r;
                })
                .toList();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static class Bucket {
        final String signature;
        int count;
        final List<String> examples = new ArrayList<>(3);
        Bucket(String signature) { this.signature = signature; }
        void add(NormalizedEvent e) {
            count++;
            if (examples.size() < 3) examples.add(e.message() == null ? "" : e.message());
        }
    }
}
