package com.watchdog.api;

import com.watchdog.agent.AgentOrchestrator;
import com.watchdog.config.WatchdogProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Tells the UI the agent layer isn't wired up so it can render a "disabled"
 * console instead of breaking the dashboard.
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@ConditionalOnMissingBean(AgentOrchestrator.class)
public class AgentStatusFallbackController {

    private final WatchdogProperties properties;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "enabled", false,
                "mode", properties.getAgent().getMode(),
                "model", properties.getAihub().getModel(),
                "message", "Agent layer disabled. Set AGENT_ENABLED=true and configure LLM_BASE_URL / LLM_API_KEY / LLM_MODEL."));
    }
}
