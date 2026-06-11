package com.watchdog.agent.tools;

import com.watchdog.agent.AgentTool;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.remediation.KubernetesClientWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PodStatusTool implements AgentTool {

    private final KubernetesClientWrapper k8s;

    @Override
    public String name() {
        return "pod_status";
    }

    @Override
    public String description() {
        return "Read-only pod status from Kubernetes for a service. Returns phase, restart count, container readiness. Does NOT mutate anything.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "namespace", Map.of("type", "string"),
                        "service", Map.of("type", "string", "description", "value of the app= label")),
                "required", List.of("namespace", "service"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String ns = args == null ? "" : String.valueOf(args.getOrDefault("namespace", ""));
        String svc = args == null ? "" : String.valueOf(args.getOrDefault("service", ""));
        if (ns.isBlank() || svc.isBlank()) {
            return ToolResult.error("namespace and service are required");
        }
        try {
            List<Map<String, Object>> pods = k8s.listPodStatus(ns, svc);
            List<AgentEvidence> evidence = new ArrayList<>();
            for (Map<String, Object> pod : pods) {
                evidence.add(new AgentEvidence("pod",
                        "ns=" + ns + "/" + pod.get("name"),
                        "phase=" + pod.get("phase") + " restarts=" + pod.get("restartCount")));
            }
            return ToolResult.ok(Map.of("count", pods.size(), "pods", pods), evidence);
        } catch (Exception e) {
            return ToolResult.error("pod_status failed: " + e.getMessage());
        }
    }
}
