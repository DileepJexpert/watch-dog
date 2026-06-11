package com.watchdog.api;

import com.watchdog.agent.AgentOrchestrator;
import com.watchdog.agent.dto.AgentAnswer;
import com.watchdog.agent.dto.AgentAskRequest;
import com.watchdog.config.WatchdogProperties;
import com.watchdog.knowledge.KnowledgeDocEntity;
import com.watchdog.knowledge.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FR-3 conversational interface.
 *
 *  POST /api/agent/ask           synchronous, returns the final AgentAnswer
 *  POST /api/agent/ask/stream    fire-and-forget, streams step events to
 *                                /topic/agent/{sessionId} on the /ws/agent broker
 *  POST /api/agent/knowledge     ingest a runbook (admin / setup helper)
 *  GET  /api/agent/status        config sanity check from the UI
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@ConditionalOnBean(AgentOrchestrator.class)
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final KnowledgeService knowledgeService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WatchdogProperties properties;

    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-stream");
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/ask")
    public ResponseEntity<AgentAnswer> ask(@RequestBody AgentAskRequest request) {
        String sessionId = request.sessionId() == null ? UUID.randomUUID().toString() : request.sessionId();
        AgentAnswer answer = orchestrator.ask(sessionId, request.question(), request.history());
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/ask/stream")
    public ResponseEntity<Map<String, String>> askStream(@RequestBody AgentAskRequest request) {
        String sessionId = request.sessionId() == null ? UUID.randomUUID().toString() : request.sessionId();
        String topic = "/topic/agent/" + sessionId;

        CompletableFuture.runAsync(() -> {
            try {
                AgentAnswer answer = orchestrator.ask(sessionId, request.question(), request.history(),
                        step -> messagingTemplate.convertAndSend(topic, Map.of(
                                "type", "step",
                                "payload", step)));
                messagingTemplate.convertAndSend(topic, Map.of(
                        "type", "final",
                        "payload", answer));
            } catch (Exception e) {
                log.error("Stream ask failed: {}", e.getMessage(), e);
                messagingTemplate.convertAndSend(topic, Map.of(
                        "type", "error",
                        "payload", Map.of("message", e.getMessage() == null ? "unknown error" : e.getMessage())));
            }
        }, streamExecutor);

        return ResponseEntity.accepted().body(Map.of("sessionId", sessionId, "topic", topic));
    }

    @PostMapping("/knowledge")
    public ResponseEntity<KnowledgeDocEntity> ingest(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "untitled");
        String content = body.getOrDefault("content", "");
        return ResponseEntity.ok(knowledgeService.ingestRunbook(title, content));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var cfg = properties.getAgent();
        var aihub = properties.getAihub();
        return ResponseEntity.ok(Map.of(
                "enabled", cfg.isEnabled(),
                "mode", cfg.getMode(),
                "maxSteps", cfg.getMaxSteps(),
                "model", aihub.getModel(),
                "baseUrlConfigured", aihub.getBaseUrl() != null && !aihub.getBaseUrl().isBlank(),
                "redactionEnabled", cfg.getRedaction().isEnabled()));
    }
}
