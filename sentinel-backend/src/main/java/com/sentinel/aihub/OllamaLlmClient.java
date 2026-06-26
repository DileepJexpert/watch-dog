package com.sentinel.aihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.aihub.AnthropicLlmClient.LlmException;
import com.sentinel.aihub.model.*;
import com.sentinel.config.SentinelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * Native Ollama LlmClient — talks directly to Ollama's /api/chat endpoint.
 *
 * Removes the need for a LiteLLM proxy: SENTINEL can call any locally-installed
 * Ollama model directly. The model id is read from
 * {@link SentinelProperties.AihubConfig#getModel()} on every call so the model
 * can be switched at runtime via {@code POST /api/agent/model}.
 *
 * Selected when {@code sentinel.aihub.provider=ollama}.
 */
@Slf4j
@RequiredArgsConstructor
public class OllamaLlmClient implements LlmClient {

    private final WebClient llmWebClient;
    private final SentinelProperties properties;

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, LlmOptions opts) {
        Map<String, Object> body = buildRequestBody(messages, tools, opts);
        long timeoutMs = properties.getAihub().getTimeoutMs();
        long startNs = System.nanoTime();
        log.debug("[llm/ollama] → POST {}/api/chat model={} messages={} tools={}",
                properties.getAihub().getBaseUrl(), body.get("model"),
                messages.size(), tools == null ? 0 : tools.size());

        try {
            JsonNode response = llmWebClient.post()
                    .uri("/api/chat")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            LlmResponse parsed = parseResponse(response);
            log.debug("[llm/ollama] ← {}ms stop={} tokens={}/{} toolCalls={}",
                    (System.nanoTime() - startNs) / 1_000_000,
                    parsed.stopReason(),
                    parsed.usage().inputTokens(), parsed.usage().outputTokens(),
                    parsed.toolCalls() == null ? 0 : parsed.toolCalls().size());
            return parsed;
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            String hint = diagnose(e, ms, String.valueOf(body.get("model")));
            // Log just the message + hint, NOT the full reactor stack trace —
            // it's 60 lines of Netty internals that bury the actual cause.
            log.error("[llm/ollama] FAILED in {}ms: {} | {}", ms, e.getMessage(), hint);
            throw new LlmException(hint, e);
        }
    }

    /**
     * Turn an opaque Ollama failure into an actionable one-liner. The most
     * common one on a laptop is a 500 after a long delay — that's Ollama
     * running out of memory loading/running a big model while Docker eats RAM.
     */
    private String diagnose(Exception e, long ms, String model) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("500") && ms > 20_000) {
            return "Ollama returned 500 after " + (ms / 1000) + "s — the model '" + model
                    + "' likely ran out of memory (a 14B model needs ~10GB free; Docker + "
                    + "the infra stack may be starving it). Switch to a smaller model "
                    + "(llama3.1:8b) via the AI Copilot model dropdown, close other apps, "
                    + "or set LLM_PROVIDER=anthropic with an API key.";
        }
        if (msg.contains("timeout") || msg.contains("ReadTimeout")) {
            return "Ollama timed out after " + (ms / 1000) + "s — the model is too slow on "
                    + "this hardware for the tool-calling prompt. Try llama3.1:8b or raise "
                    + "LLM_TIMEOUT_MS.";
        }
        if (msg.contains("Connection refused") || msg.contains("ConnectException")) {
            return "Cannot reach Ollama at " + properties.getAihub().getBaseUrl()
                    + " — is `ollama serve` running? (`ollama list` to check)";
        }
        return "Ollama call failed: " + msg;
    }

    Map<String, Object> buildRequestBody(List<ChatMessage> messages, List<ToolSpec> tools, LlmOptions opts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", opts != null && opts.model() != null
                ? opts.model() : properties.getAihub().getModel());
        body.put("stream", false);

        Map<String, Object> options = new LinkedHashMap<>();
        int maxTokens = opts != null && opts.maxTokens() != null
                ? opts.maxTokens() : properties.getAihub().getMaxTokens();
        options.put("num_predict", maxTokens);
        // Ollama defaults num_ctx to 2048 tokens. Our system prompt (hard rules
        // + heuristics + worked example) plus ~12 tool JSON schemas easily
        // exceeds that, which makes the model silently lose sight of the tools
        // or the request error out. Give it a 8k window so the whole prompt
        // fits. This raises memory use modestly but is essential for reliable
        // tool calling.
        options.put("num_ctx", 8192);
        if (opts != null && opts.temperature() != null) {
            options.put("temperature", opts.temperature());
        }
        body.put("options", options);

        body.put("messages", encodeMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", encodeTools(tools));
        }
        return body;
    }

    private List<Map<String, Object>> encodeMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> out.add(Map.of("role", "system", "content", safe(m.content())));
                case USER -> out.add(Map.of("role", "user", "content", safe(m.content())));
                case ASSISTANT -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", "assistant");
                    msg.put("content", safe(m.content()));
                    if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                        List<Map<String, Object>> calls = new ArrayList<>();
                        for (ToolCall tc : m.toolCalls()) {
                            calls.add(Map.of("function", Map.of(
                                    "name", tc.name(),
                                    "arguments", tc.args() == null ? Map.of() : tc.args())));
                        }
                        msg.put("tool_calls", calls);
                    }
                    out.add(msg);
                }
                case TOOL -> out.add(Map.of(
                        "role", "tool",
                        "content", safe(m.content())));
            }
        }
        return out;
    }

    private List<Map<String, Object>> encodeTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec t : tools) {
            out.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", t.name(),
                            "description", t.description() == null ? "" : t.description(),
                            "parameters", t.inputSchema() == null
                                    ? Map.of("type", "object", "properties", Map.of())
                                    : t.inputSchema())));
        }
        return out;
    }

    LlmResponse parseResponse(JsonNode response) {
        if (response == null) {
            throw new LlmException("Empty Ollama response");
        }
        JsonNode message = response.path("message");
        String text = message.path("content").asText("");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcArray = message.path("tool_calls");
        if (tcArray.isArray()) {
            for (JsonNode tc : tcArray) {
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText();
                if (name == null || name.isBlank()) continue;
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode argsNode = fn.path("arguments");
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), unwrap(e.getValue())));
                }
                String id = "call_" + UUID.randomUUID().toString().substring(0, 8);
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        int inputTokens = response.path("prompt_eval_count").asInt(0);
        int outputTokens = response.path("eval_count").asInt(0);
        Usage usage = new Usage(inputTokens, outputTokens);

        String stopReason = response.path("done_reason").asText(
                response.path("done").asBoolean(false) ? "stop" : "");
        return new LlmResponse(text, toolCalls, usage, stopReason);
    }

    private Object unwrap(JsonNode n) {
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isInt()) return n.asInt();
        if (n.isLong()) return n.asLong();
        if (n.isDouble() || n.isFloat()) return n.asDouble();
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(child -> list.add(unwrap(child)));
            return list;
        }
        if (n.isObject()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> obj.put(e.getKey(), unwrap(e.getValue())));
            return obj;
        }
        return null;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
