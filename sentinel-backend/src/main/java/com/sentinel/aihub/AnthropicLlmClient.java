package com.sentinel.aihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.aihub.model.*;
import com.sentinel.config.SentinelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * Default LlmClient: Anthropic /v1/messages compatible. Works against Anthropic,
 * AWS Bedrock Anthropic, or IDFC AI Hub (provided it speaks the same envelope).
 *
 * Repointing to a different endpoint is a config-only change (FR-1): set
 * LLM_BASE_URL, LLM_MODEL, LLM_API_KEY.
 */
@Slf4j
@RequiredArgsConstructor
public class AnthropicLlmClient implements LlmClient {

    private final WebClient llmWebClient;
    private final SentinelProperties properties;

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, LlmOptions opts) {
        Map<String, Object> body = buildRequestBody(messages, tools, opts);
        long timeoutMs = properties.getAihub().getTimeoutMs();
        long startNs = System.nanoTime();
        log.debug("[llm/anthropic] → POST {}/v1/messages model={} messages={} tools={}",
                properties.getAihub().getBaseUrl(), body.get("model"),
                messages.size(), tools == null ? 0 : tools.size());

        try {
            JsonNode response = llmWebClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", properties.getAihub().getApiKey())
                    .header("anthropic-version", properties.getAihub().getAnthropicVersion())
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            LlmResponse parsed = parseResponse(response);
            log.debug("[llm/anthropic] ← {}ms stop={} tokens={}/{} toolCalls={}",
                    (System.nanoTime() - startNs) / 1_000_000,
                    parsed.stopReason(),
                    parsed.usage().inputTokens(), parsed.usage().outputTokens(),
                    parsed.toolCalls() == null ? 0 : parsed.toolCalls().size());
            return parsed;
        } catch (Exception e) {
            log.error("[llm/anthropic] FAILED in {}ms: {}",
                    (System.nanoTime() - startNs) / 1_000_000, e.getMessage());
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    Map<String, Object> buildRequestBody(List<ChatMessage> messages, List<ToolSpec> tools, LlmOptions opts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", opts != null && opts.model() != null
                ? opts.model() : properties.getAihub().getModel());
        body.put("max_tokens", opts != null && opts.maxTokens() != null
                ? opts.maxTokens() : properties.getAihub().getMaxTokens());
        if (opts != null && opts.temperature() != null) {
            body.put("temperature", opts.temperature());
        }

        String system = extractSystemPrompt(messages);
        if (system != null && !system.isBlank()) {
            body.put("system", system);
        }

        body.put("messages", encodeMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", encodeTools(tools));
        }
        return body;
    }

    private String extractSystemPrompt(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.role() == ChatRole.SYSTEM)
                .map(ChatMessage::content)
                .findFirst()
                .orElse(null);
    }

    /**
     * Encode non-system messages into Anthropic's content-block format.
     * Tool calls become assistant tool_use blocks; tool results become user tool_result blocks.
     */
    private List<Map<String, Object>> encodeMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();

        for (ChatMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> { /* hoisted into top-level system field */ }
                case USER -> {
                    flushToolResults(encoded, pendingToolResults);
                    encoded.add(Map.of("role", "user", "content", m.content()));
                }
                case ASSISTANT -> {
                    flushToolResults(encoded, pendingToolResults);
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    if (m.content() != null && !m.content().isBlank()) {
                        blocks.add(Map.of("type", "text", "text", m.content()));
                    }
                    if (m.toolCalls() != null) {
                        for (ToolCall tc : m.toolCalls()) {
                            blocks.add(Map.of(
                                    "type", "tool_use",
                                    "id", tc.id(),
                                    "name", tc.name(),
                                    "input", tc.args() != null ? tc.args() : Map.of()));
                        }
                    }
                    encoded.add(Map.of("role", "assistant", "content", blocks));
                }
                case TOOL -> pendingToolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", m.toolCallId(),
                        "content", m.content() == null ? "" : m.content()));
            }
        }
        flushToolResults(encoded, pendingToolResults);
        return encoded;
    }

    private void flushToolResults(List<Map<String, Object>> encoded, List<Map<String, Object>> pending) {
        if (pending.isEmpty()) return;
        encoded.add(Map.of("role", "user", "content", new ArrayList<>(pending)));
        pending.clear();
    }

    private List<Map<String, Object>> encodeTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec t : tools) {
            out.add(Map.of(
                    "name", t.name(),
                    "description", t.description() == null ? "" : t.description(),
                    "input_schema", t.inputSchema() == null
                            ? Map.of("type", "object", "properties", Map.of())
                            : t.inputSchema()));
        }
        return out;
    }

    LlmResponse parseResponse(JsonNode response) {
        if (response == null) {
            throw new LlmException("Empty LLM response");
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (JsonNode block : response.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                String id = block.path("id").asText();
                String name = block.path("name").asText();
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode input = block.path("input");
                input.fields().forEachRemaining(e -> args.put(e.getKey(), unwrap(e.getValue())));
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        JsonNode usageNode = response.path("usage");
        Usage usage = new Usage(
                usageNode.path("input_tokens").asInt(0),
                usageNode.path("output_tokens").asInt(0));

        String stopReason = response.path("stop_reason").asText("");
        return new LlmResponse(text.toString(), toolCalls, usage, stopReason);
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

    public static class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) { super(message, cause); }
        public LlmException(String message) { super(message); }
    }
}
