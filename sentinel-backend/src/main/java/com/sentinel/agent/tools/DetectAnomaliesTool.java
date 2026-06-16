package com.sentinel.agent.tools;

import com.sentinel.agent.AgentTool;
import com.sentinel.agent.dto.AgentEvidence;
import com.sentinel.correlation.SlidingWindowBuffer;
import com.sentinel.intelligence.anomaly.ZScoreDetector;
import com.sentinel.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Runs every event in the service's current window through the Z-score detector
 * and returns only the anomalous ones.
 */
@Component
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DetectAnomaliesTool implements AgentTool {

    private final ZScoreDetector zScoreDetector;
    private final SlidingWindowBuffer windowBuffer;

    @Override
    public String name() {
        return "detect_anomalies";
    }

    @Override
    public String description() {
        return "Apply the Z-score detector to the current sliding window for a service and return any anomalous metric events with z-scores.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("service", Map.of("type", "string")),
                "required", List.of("service"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String service = args == null ? "" : String.valueOf(args.getOrDefault("service", ""));
        if (service.isBlank()) return ToolResult.error("service is required");

        try {
            List<NormalizedEvent> window = windowBuffer.getWindow(service);
            List<Map<String, Object>> anomalies = new ArrayList<>();
            List<AgentEvidence> evidence = new ArrayList<>();
            for (NormalizedEvent e : window) {
                NormalizedEvent enriched = zScoreDetector.detectAndEnrich(e);
                if (Boolean.TRUE.equals(enriched.attributes().get("anomaly_detected"))) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("service", enriched.serviceName());
                    row.put("metric", enriched.attributes().get("metric_name"));
                    row.put("value", enriched.attributes().get("metric_value"));
                    row.put("zScore", enriched.attributes().get("z_score"));
                    row.put("baselineMean", enriched.attributes().get("baseline_mean"));
                    row.put("baselineStdDev", enriched.attributes().get("baseline_std_dev"));
                    anomalies.add(row);
                    evidence.add(new AgentEvidence("anomaly",
                            "service=" + enriched.serviceName() + "; metric=" + row.get("metric"),
                            "z=" + row.get("zScore")));
                }
            }
            return ToolResult.ok(Map.of("count", anomalies.size(), "anomalies", anomalies), evidence);
        } catch (Exception e) {
            return ToolResult.error("detect_anomalies failed: " + e.getMessage());
        }
    }
}
