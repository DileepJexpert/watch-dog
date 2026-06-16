package com.sentinel.api;

import com.sentinel.agent.AgentOrchestrator;
import com.sentinel.agent.dto.AgentAnswer;
import com.sentinel.agent.dto.AgentAskRequest;
import com.sentinel.aihub.OllamaModelService;
import com.sentinel.config.SentinelProperties;
import com.sentinel.knowledge.KnowledgeDocEntity;
import com.sentinel.knowledge.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final SentinelProperties properties;
    private final OllamaModelService ollamaModels;

    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-stream");
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/ask")
    public ResponseEntity<AgentAnswer> ask(@RequestBody AgentAskRequest request) {
        String sessionId = request.sessionId() == null ? UUID.randomUUID().toString() : request.sessionId();
        log.debug("[api/ask] sync session={} historySize={} question='{}'",
                sessionId, request.history() == null ? 0 : request.history().size(),
                request.question() == null ? "" : request.question());
        AgentAnswer answer = orchestrator.ask(sessionId, request.question(), request.history());
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/ask/stream")
    public ResponseEntity<Map<String, String>> askStream(@RequestBody AgentAskRequest request) {
        String sessionId = request.sessionId() == null ? UUID.randomUUID().toString() : request.sessionId();
        String topic = "/topic/agent/" + sessionId;
        log.debug("[api/ask/stream] session={} topic={} historySize={} question='{}'",
                sessionId, topic, request.history() == null ? 0 : request.history().size(),
                request.question() == null ? "" : request.question());

        CompletableFuture.runAsync(() -> {
            try {
                AgentAnswer answer = orchestrator.ask(sessionId, request.question(), request.history(),
                        step -> {
                            log.debug("[ws/agent] → step session={} step={} toolCalls={}",
                                    sessionId, step.get("step"), step.get("toolCalls"));
                            messagingTemplate.convertAndSend(topic, Map.of(
                                    "type", "step",
                                    "payload", step));
                        });
                log.debug("[ws/agent] → final session={} summary={} chars",
                        sessionId, answer == null || answer.summary() == null ? 0 : answer.summary().length());
                messagingTemplate.convertAndSend(topic, Map.of(
                        "type", "final",
                        "payload", answer));
            } catch (Exception e) {
                log.error("[ws/agent] → error session={} : {}", sessionId, e.getMessage(), e);
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("mode", cfg.getMode());
        out.put("maxSteps", cfg.getMaxSteps());
        out.put("provider", aihub.getProvider());
        out.put("model", aihub.getModel());
        out.put("baseUrlConfigured", aihub.getBaseUrl() != null && !aihub.getBaseUrl().isBlank());
        out.put("redactionEnabled", cfg.getRedaction().isEnabled());
        out.put("modelSwitchSupported", "ollama".equalsIgnoreCase(aihub.getProvider()));
        return ResponseEntity.ok(out);
    }

    /**
     * Lists locally-installed Ollama models so the UI dropdown can render them.
     * Returns an empty list when the provider is not Ollama, or when Ollama is
     * unreachable.
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models() {
        List<Map<String, Object>> installed = ollamaModels.listInstalled();
        return ResponseEntity.ok(Map.of(
                "provider", properties.getAihub().getProvider(),
                "active", properties.getAihub().getModel(),
                "models", installed));
    }

    /**
     * Switches the active model at runtime. Takes effect on the next agent run —
     * no rebean, no restart. Only meaningful when provider=ollama.
     */
    @PostMapping("/model")
    public ResponseEntity<Map<String, Object>> setModel(@RequestBody Map<String, String> body) {
        String model = body.get("model");
        log.debug("[api/model] switch requested → {}", model);
        if (model == null || model.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "model is required"));
        }
        if (!"ollama".equalsIgnoreCase(properties.getAihub().getProvider())) {
            log.debug("[api/model] rejected — provider={} (only ollama supported)", properties.getAihub().getProvider());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Runtime model switching is only supported when provider=ollama. "
                            + "For Anthropic providers, set LLM_MODEL via env vars."));
        }
        ollamaModels.setActiveModel(model);
        return ResponseEntity.ok(Map.of(
                "active", ollamaModels.getActiveModel(),
                "message", "Active model updated"));
    }
}
