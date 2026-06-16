package com.watchdog.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchdog.agent.dto.AgentAnswer;
import com.watchdog.agent.dto.AgentEvidence;
import com.watchdog.agent.dto.RecommendedAction;
import com.watchdog.agent.dto.RootCause;
import com.watchdog.aihub.LlmClient;
import com.watchdog.aihub.model.*;
import com.watchdog.config.WatchdogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * FR-2 plan → call tool → observe → reason → answer loop.
 *
 * Hard step cap enforced via {@code watchdog.agent.max-steps}. Independent tool
 * calls in a single LLM turn execute in parallel. Tool outputs are PII-redacted
 * before being sent back to the model. Final answer is mapped into the FR-9
 * AgentAnswer schema with evidence pulled from every tool call that fed the
 * conversation.
 */
@Slf4j
@Service
@ConditionalOnBean(LlmClient.class)
public class AgentOrchestrator {

    private static final String SYSTEM_PROMPT = """
            You are WATCHDOG, the production support copilot for an internal banking
            platform. Your job is to help on-call engineers diagnose live API
            incidents using the tools you have been given.

            Rules:
            - Use tools to gather evidence before forming hypotheses; never assert a
              cause you cannot tie to a retrieved signal.
            - When tool calls are independent, request them together so they run in
              parallel.
            - You are in DECISION SUPPORT mode. You may RECOMMEND a remediation
              and explain why, but you MUST NOT execute it. Any remediation step
              must be marked as requiring human approval.
            - Be concise. Cite specific log lines, trace IDs, metric values, or
              knowledge documents in your evidence.
            - When you have enough information, call the tool `finalize_answer` with
              the structured payload. After that, do not call any further tools.

            FR-9 output contract enforced via the finalize_answer tool:
            {
              "summary": "...",
              "rootCause": { "hypothesis": "...", "confidence": "low|medium|high" },
              "evidence": [ { "source": "...", "ref": "...", "excerpt": "..." } ],
              "recommendedActions": [ { "action": "...", "rationale": "...", "requiresApproval": true } ]
            }
            """;

    private static final String FINALIZE_TOOL = "finalize_answer";

    private final LlmClient llmClient;
    private final List<AgentTool> tools;
    private final WatchdogProperties properties;
    private final PiiRedactor redactor;
    private final AgentAuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExecutorService toolExecutor;

    public AgentOrchestrator(LlmClient llmClient,
                             List<AgentTool> tools,
                             WatchdogProperties properties,
                             PiiRedactor redactor,
                             AgentAuditService auditService) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.properties = properties;
        this.redactor = redactor;
        this.auditService = auditService;
        this.objectMapper = new ObjectMapper();
        this.toolExecutor = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "agent-tool");
            t.setDaemon(true);
            return t;
        });
    }

    public AgentAnswer ask(String sessionId, String question, List<ChatMessage> history) {
        return ask(sessionId, question, history, null);
    }

    /**
     * Run the agent loop. {@code stepObserver} (optional) is called after every
     * model turn — used by the WebSocket controller to stream progress.
     */
    public AgentAnswer ask(String sessionId,
                            String question,
                            List<ChatMessage> history,
                            Consumer<Map<String, Object>> stepObserver) {
        long startNs = System.nanoTime();
        AgentMode mode = AgentMode.parse(properties.getAgent().getMode());
        List<AgentTool> active = activeTools(mode);
        List<ToolSpec> specs = buildToolSpecs(active);
        log.info("[agent] ask session={} mode={} maxSteps={} historySize={} activeTools={} question='{}'",
                sessionId, mode, properties.getAgent().getMaxSteps(),
                history == null ? 0 : history.size(), active.size(),
                truncate(question, 200));

        List<ChatMessage> messages = buildInitialMessages(question, history);
        List<Map<String, Object>> traceLog = new ArrayList<>();
        List<AgentEvidence> aggregatedEvidence = new ArrayList<>();
        List<ToolCall> allCalls = new ArrayList<>();
        Usage cumulativeUsage = new Usage(0, 0);
        int maxSteps = Math.max(1, properties.getAgent().getMaxSteps());

        AgentAnswer answer = null;
        String error = null;
        int stepsTaken = 0;
        try {
            for (int step = 0; step < maxSteps; step++) {
                stepsTaken = step + 1;
                long llmStartNs = System.nanoTime();
                log.debug("[agent] session={} step={} → LLM call (messages={}, tools={})",
                        sessionId, stepsTaken, messages.size(), specs.size());
                LlmResponse response = llmClient.chat(messages, specs, LlmOptions.defaults());
                long llmMs = (System.nanoTime() - llmStartNs) / 1_000_000;
                cumulativeUsage = add(cumulativeUsage, response.usage());
                int callCount = response.toolCalls() == null ? 0 : response.toolCalls().size();
                log.info("[agent] session={} step={} LLM done in {}ms — text={} chars, toolCalls={}, in/out tokens={}/{}",
                        sessionId, stepsTaken, llmMs,
                        response.text() == null ? 0 : response.text().length(),
                        callCount, response.usage().inputTokens(), response.usage().outputTokens());
                if (log.isDebugEnabled() && callCount > 0) {
                    for (ToolCall tc : response.toolCalls()) {
                        log.debug("[agent]   ↳ tool_call name={} id={} args={}",
                                tc.name(), tc.id(), tc.args());
                    }
                }

                Map<String, Object> stepRecord = new LinkedHashMap<>();
                stepRecord.put("step", stepsTaken);
                stepRecord.put("text", response.text());
                stepRecord.put("toolCalls", response.toolCalls() == null ? List.of()
                        : response.toolCalls().stream().map(this::toolCallToMap).toList());
                traceLog.add(stepRecord);
                if (stepObserver != null) stepObserver.accept(stepRecord);

                if (!response.hasToolCalls()) {
                    log.info("[agent] session={} step={} — model returned no tool_calls; treating text as final answer",
                            sessionId, stepsTaken);
                    answer = fallbackAnswer(response.text(), aggregatedEvidence, traceLog);
                    break;
                }

                Optional<ToolCall> finalizeCall = response.toolCalls().stream()
                        .filter(tc -> FINALIZE_TOOL.equals(tc.name()))
                        .findFirst();
                if (finalizeCall.isPresent()) {
                    log.info("[agent] session={} step={} — finalize_answer called, exiting loop", sessionId, stepsTaken);
                    allCalls.add(finalizeCall.get());
                    answer = buildFinalAnswer(finalizeCall.get(), aggregatedEvidence, traceLog);
                    break;
                }

                messages.add(ChatMessage.assistant(response.text(), response.toolCalls()));
                allCalls.addAll(response.toolCalls());
                long toolStartNs = System.nanoTime();
                List<ChatMessage> toolResults = executeInParallel(response.toolCalls(), active, aggregatedEvidence);
                log.info("[agent] session={} step={} ran {} tool(s) in {}ms — evidence so far: {}",
                        sessionId, stepsTaken, response.toolCalls().size(),
                        (System.nanoTime() - toolStartNs) / 1_000_000, aggregatedEvidence.size());
                messages.addAll(toolResults);
            }

            if (answer == null) {
                answer = fallbackAnswer("Agent reached the step cap before finalizing.",
                        aggregatedEvidence, traceLog);
            }
            return answer;
        } catch (Exception e) {
            error = e.getMessage();
            log.error("Agent loop failed: {}", e.getMessage(), e);
            answer = errorAnswer(e.getMessage(), aggregatedEvidence, traceLog);
            return answer;
        } finally {
            long totalMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[agent] session={} DONE — steps={} totalMs={} tokens(in/out)={}/{} evidence={} error={}",
                    sessionId, stepsTaken, totalMs,
                    cumulativeUsage.inputTokens(), cumulativeUsage.outputTokens(),
                    aggregatedEvidence.size(), error == null ? "none" : error);
            auditService.record(sessionId, question, answer,
                    mode.name().toLowerCase(),
                    properties.getAihub().getModel(),
                    stepsTaken, cumulativeUsage, allCalls, error);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private List<AgentTool> activeTools(AgentMode mode) {
        List<AgentTool> active = new ArrayList<>();
        for (AgentTool tool : tools) {
            if (mode == AgentMode.ADVISORY && tool.isMutating()) {
                log.debug("Excluding mutating tool '{}' in advisory mode", tool.name());
                continue;
            }
            active.add(tool);
        }
        return active;
    }

    private List<ToolSpec> buildToolSpecs(List<AgentTool> active) {
        List<ToolSpec> specs = new ArrayList<>();
        for (AgentTool tool : active) {
            specs.add(new ToolSpec(tool.name(), tool.description(), tool.jsonSchema()));
        }
        specs.add(new ToolSpec(FINALIZE_TOOL,
                "Submit the final answer for the user in the FR-9 schema. Call this exactly once when ready.",
                finalizeSchema()));
        return specs;
    }

    private Map<String, Object> finalizeSchema() {
        Map<String, Object> evidenceItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "source", Map.of("type", "string"),
                        "ref", Map.of("type", "string"),
                        "excerpt", Map.of("type", "string")),
                "required", List.of("source", "ref"));
        Map<String, Object> actionItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string"),
                        "rationale", Map.of("type", "string"),
                        "requiresApproval", Map.of("type", "boolean")),
                "required", List.of("action", "rationale"));
        Map<String, Object> rootCause = Map.of(
                "type", "object",
                "properties", Map.of(
                        "hypothesis", Map.of("type", "string"),
                        "confidence", Map.of("type", "string", "enum", List.of("low", "medium", "high"))),
                "required", List.of("hypothesis", "confidence"));

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "string"),
                        "rootCause", rootCause,
                        "evidence", Map.of("type", "array", "items", evidenceItem),
                        "recommendedActions", Map.of("type", "array", "items", actionItem)),
                "required", List.of("summary"));
    }

    private List<ChatMessage> buildInitialMessages(String question, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        if (history != null) {
            int cap = properties.getAgent().getHistoryMaxMessages();
            int start = Math.max(0, history.size() - cap);
            for (int i = start; i < history.size(); i++) {
                ChatMessage h = history.get(i);
                if (h.role() == ChatRole.SYSTEM) continue;
                messages.add(h);
            }
        }
        messages.add(ChatMessage.user(question));
        return messages;
    }

    private List<ChatMessage> executeInParallel(List<ToolCall> toolCalls,
                                                 List<AgentTool> active,
                                                 List<AgentEvidence> aggregatedEvidence) {
        Map<String, AgentTool> byName = new HashMap<>();
        for (AgentTool t : active) byName.put(t.name(), t);

        List<Future<ChatMessage>> futures = new ArrayList<>();
        Map<Future<ChatMessage>, ToolCall> futureToCall = new HashMap<>();
        for (ToolCall call : toolCalls) {
            Future<ChatMessage> f = toolExecutor.submit(() -> runOne(call, byName, aggregatedEvidence));
            futures.add(f);
            futureToCall.put(f, call);
        }

        List<ChatMessage> results = new ArrayList<>();
        for (Future<ChatMessage> f : futures) {
            try {
                results.add(f.get(properties.getAihub().getTimeoutMs(), TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                ToolCall call = futureToCall.get(f);
                log.warn("Tool execution failed for {}: {}", call.name(), e.getMessage());
                results.add(ChatMessage.tool(call.id(),
                        "{\"error\":\"tool execution failed: " + safeJson(e.getMessage()) + "\"}"));
            }
        }
        return results;
    }

    private ChatMessage runOne(ToolCall call,
                                Map<String, AgentTool> byName,
                                List<AgentEvidence> aggregatedEvidence) {
        AgentTool tool = byName.get(call.name());
        if (tool == null) {
            log.warn("[tool] unknown tool requested: name='{}' id={}", call.name(), call.id());
            return ChatMessage.tool(call.id(), "{\"error\":\"unknown tool: " + call.name() + "\"}");
        }
        long startNs = System.nanoTime();
        log.debug("[tool] → {} id={} args={}", call.name(), call.id(), call.args());
        try {
            AgentTool.ToolResult result = tool.execute(call.args() == null ? Map.of() : call.args());
            Object redactedPayload = redactor.redact(result.payload());
            if (result.evidence() != null) aggregatedEvidence.addAll(result.evidence());
            String json = toJson(redactedPayload);
            log.info("[tool] ← {} id={} OK in {}ms — evidence+={}, payload={} chars",
                    call.name(), call.id(), (System.nanoTime() - startNs) / 1_000_000,
                    result.evidence() == null ? 0 : result.evidence().size(), json.length());
            return ChatMessage.tool(call.id(), json);
        } catch (Exception e) {
            log.warn("[tool] ← {} id={} FAILED in {}ms: {}",
                    call.name(), call.id(), (System.nanoTime() - startNs) / 1_000_000, e.getMessage());
            return ChatMessage.tool(call.id(),
                    "{\"error\":\"" + safeJson(e.getMessage()) + "\"}");
        }
    }

    private AgentAnswer buildFinalAnswer(ToolCall finalize,
                                         List<AgentEvidence> aggregatedEvidence,
                                         List<Map<String, Object>> traceLog) {
        Map<String, Object> args = finalize.args() == null ? Map.of() : finalize.args();
        String summary = stringOf(args, "summary", "");

        RootCause rootCause = null;
        Object rc = args.get("rootCause");
        if (rc instanceof Map<?, ?> rcMap) {
            String hypothesis = stringOf(rcMap, "hypothesis", "");
            String confidence = stringOf(rcMap, "confidence", "low");
            rootCause = new RootCause(hypothesis, Confidence.parse(confidence).name().toLowerCase());
        }

        List<AgentEvidence> evidence = parseEvidence(args.get("evidence"), aggregatedEvidence);
        List<RecommendedAction> actions = parseActions(args.get("recommendedActions"));

        return new AgentAnswer(summary, rootCause, evidence, actions, traceLog);
    }

    private List<AgentEvidence> parseEvidence(Object raw, List<AgentEvidence> aggregated) {
        List<AgentEvidence> evidence = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    evidence.add(new AgentEvidence(
                            stringOf(m, "source", "unknown"),
                            stringOf(m, "ref", ""),
                            stringOf(m, "excerpt", "")));
                }
            }
        }
        if (evidence.isEmpty()) return new ArrayList<>(aggregated);
        return evidence;
    }

    private List<RecommendedAction> parseActions(Object raw) {
        List<RecommendedAction> actions = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    actions.add(new RecommendedAction(
                            stringOf(m, "action", ""),
                            stringOf(m, "rationale", ""),
                            true));
                }
            }
        }
        return actions;
    }

    private AgentAnswer fallbackAnswer(String text,
                                       List<AgentEvidence> evidence,
                                       List<Map<String, Object>> traceLog) {
        return new AgentAnswer(text == null ? "" : text, null,
                new ArrayList<>(evidence), List.of(), traceLog);
    }

    private AgentAnswer errorAnswer(String error,
                                    List<AgentEvidence> evidence,
                                    List<Map<String, Object>> traceLog) {
        return new AgentAnswer("Agent failed: " + (error == null ? "unknown error" : error),
                null, new ArrayList<>(evidence), List.of(), traceLog);
    }

    private String stringOf(Map<?, ?> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private Map<String, Object> toolCallToMap(ToolCall tc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tc.id());
        m.put("name", tc.name());
        m.put("args", tc.args() == null ? Map.of() : tc.args());
        return m;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"unable to serialize tool result\"}";
        }
    }

    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Usage add(Usage a, Usage b) {
        if (a == null) a = new Usage(0, 0);
        if (b == null) b = new Usage(0, 0);
        return new Usage(a.inputTokens() + b.inputTokens(), a.outputTokens() + b.outputTokens());
    }
}
