package com.watchdog.aihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchdog.aihub.model.*;
import com.watchdog.config.WatchdogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies OllamaLlmClient encodes requests into Ollama's /api/chat format
 * (NOT Anthropic's /v1/messages) and parses Ollama responses correctly.
 *
 * This is the native-Ollama path that removes the need for a LiteLLM proxy.
 */
class OllamaLlmClientTest {

    private WatchdogProperties properties;
    private OllamaLlmClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        properties = new WatchdogProperties();
        properties.getAihub().setProvider("ollama");
        properties.getAihub().setBaseUrl("http://localhost:11434");
        properties.getAihub().setModel("qwen2.5-coder:14b");
        properties.getAihub().setMaxTokens(2000);
        client = new OllamaLlmClient(WebClient.create(), properties);
        mapper = new ObjectMapper();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBody_putsSystemAsTopLevelMessageNotSeparateField() {
        // Ollama (unlike Anthropic) takes system as a role inside messages[], not a top-level field.
        List<ChatMessage> messages = List.of(
                ChatMessage.system("you are watchdog"),
                ChatMessage.user("why is payments slow?"));

        Map<String, Object> body = client.buildRequestBody(messages, List.of(), LlmOptions.defaults());

        assertThat(body).containsEntry("model", "qwen2.5-coder:14b");
        assertThat(body).containsEntry("stream", false);
        assertThat(body).doesNotContainKey("system");

        List<Map<String, Object>> encoded = (List<Map<String, Object>>) body.get("messages");
        assertThat(encoded).hasSize(2);
        assertThat(encoded.get(0)).containsEntry("role", "system");
        assertThat(encoded.get(0)).containsEntry("content", "you are watchdog");
        assertThat(encoded.get(1)).containsEntry("role", "user");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBody_encodesToolsInOpenAiFunctionFormat() {
        // Ollama uses the OpenAI function-tool schema: { type: "function", function: { name, parameters } }
        List<ToolSpec> tools = List.of(new ToolSpec(
                "search_logs", "search logs",
                Map.of("type", "object", "properties", Map.of("service", Map.of("type", "string")))));

        Map<String, Object> body = client.buildRequestBody(
                List.of(ChatMessage.user("hi")), tools, LlmOptions.defaults());

        List<Map<String, Object>> encodedTools = (List<Map<String, Object>>) body.get("tools");
        assertThat(encodedTools).hasSize(1);
        Map<String, Object> tool = encodedTools.get(0);
        assertThat(tool).containsEntry("type", "function");
        Map<String, Object> fn = (Map<String, Object>) tool.get("function");
        assertThat(fn).containsEntry("name", "search_logs");
        assertThat(fn).containsKey("parameters");
        // Ollama wants `parameters` not `input_schema`
        assertThat(fn).doesNotContainKey("input_schema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBody_encodesAssistantToolCallsAsOllamaToolCalls() {
        ToolCall call = new ToolCall("tu_1", "search_logs", Map.of("service", "payments"));
        List<ChatMessage> messages = List.of(
                ChatMessage.user("why slow?"),
                ChatMessage.assistant("looking", List.of(call)),
                ChatMessage.tool("tu_1", "{\"count\":3}"));

        Map<String, Object> body = client.buildRequestBody(messages, List.of(), LlmOptions.defaults());
        List<Map<String, Object>> encoded = (List<Map<String, Object>>) body.get("messages");
        assertThat(encoded).hasSize(3);

        Map<String, Object> assistantMsg = encoded.get(1);
        assertThat(assistantMsg).containsEntry("role", "assistant");
        assertThat(assistantMsg).containsEntry("content", "looking");
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) assistantMsg.get("tool_calls");
        assertThat(toolCalls).hasSize(1);
        Map<String, Object> fn = (Map<String, Object>) toolCalls.get(0).get("function");
        assertThat(fn).containsEntry("name", "search_logs");

        Map<String, Object> toolResult = encoded.get(2);
        assertThat(toolResult).containsEntry("role", "tool");
        assertThat(toolResult).containsEntry("content", "{\"count\":3}");
    }

    @Test
    void buildRequestBody_modelCanBeOverriddenViaOptions() {
        Map<String, Object> body = client.buildRequestBody(
                List.of(ChatMessage.user("hi")), List.of(),
                new LlmOptions(null, null, "qwen2.5-coder:7b"));
        assertThat(body).containsEntry("model", "qwen2.5-coder:7b");
    }

    @Test
    void parseResponse_extractsContentAndToolCalls() throws Exception {
        String json = """
                {
                  "model": "qwen2.5-coder:14b",
                  "message": {
                    "role": "assistant",
                    "content": "Looking at logs...",
                    "tool_calls": [
                      {"function": {"name": "search_logs", "arguments": {"service": "payments", "limit": 50}}}
                    ]
                  },
                  "done": true,
                  "done_reason": "stop",
                  "prompt_eval_count": 120,
                  "eval_count": 30
                }
                """;
        JsonNode node = mapper.readTree(json);
        LlmResponse response = client.parseResponse(node);

        assertThat(response.text()).isEqualTo("Looking at logs...");
        assertThat(response.stopReason()).isEqualTo("stop");
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall tc = response.toolCalls().get(0);
        assertThat(tc.name()).isEqualTo("search_logs");
        assertThat(tc.args()).containsEntry("service", "payments");
        assertThat(tc.args()).containsEntry("limit", 50);
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.usage().outputTokens()).isEqualTo(30);
    }

    @Test
    void parseResponse_handlesEmptyToolCalls() throws Exception {
        String json = """
                {
                  "message": {"role": "assistant", "content": "Final answer here."},
                  "done": true,
                  "prompt_eval_count": 50,
                  "eval_count": 12
                }
                """;
        LlmResponse response = client.parseResponse(mapper.readTree(json));
        assertThat(response.text()).isEqualTo("Final answer here.");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void switchingModelInPropertiesTakesEffectOnNextCall_runtimeModelSwitch() {
        // Initial model
        Map<String, Object> body1 = client.buildRequestBody(
                List.of(ChatMessage.user("q")), List.of(), LlmOptions.defaults());
        assertThat(body1).containsEntry("model", "qwen2.5-coder:14b");

        // User clicks dropdown → POST /api/agent/model mutates properties
        properties.getAihub().setModel("qwen2.5-coder:7b");

        Map<String, Object> body2 = client.buildRequestBody(
                List.of(ChatMessage.user("q")), List.of(), LlmOptions.defaults());
        assertThat(body2).containsEntry("model", "qwen2.5-coder:7b");
    }
}
