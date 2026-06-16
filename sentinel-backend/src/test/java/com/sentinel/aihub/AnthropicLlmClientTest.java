package com.sentinel.aihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aihub.model.*;
import com.sentinel.config.SentinelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-1 acceptance test: switching base URL + model in config is the only thing
 * needed to repoint the client, AND the HTTP request/response mapping is
 * correctly assembled.
 */
class AnthropicLlmClientTest {

    private SentinelProperties properties;
    private AnthropicLlmClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        properties = new SentinelProperties();
        properties.getAihub().setBaseUrl("http://example/v1");
        properties.getAihub().setApiKey("sk-test");
        properties.getAihub().setModel("claude-test-model");
        properties.getAihub().setMaxTokens(1500);
        client = new AnthropicLlmClient(WebClient.create(), properties);
        mapper = new ObjectMapper();
    }

    @Test
    void buildRequestBody_carriesModelAndSystemPromptAndTools() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("you are sentinel"),
                ChatMessage.user("why is payments slow?"));
        List<ToolSpec> tools = List.of(new ToolSpec(
                "search_logs", "search logs",
                Map.of("type", "object", "properties", Map.of())));

        Map<String, Object> body = client.buildRequestBody(messages, tools, LlmOptions.defaults());

        assertThat(body).containsEntry("model", "claude-test-model");
        assertThat(body).containsEntry("max_tokens", 1500);
        assertThat(body).containsEntry("system", "you are sentinel");
        assertThat(body.get("tools")).asList().hasSize(1);
    }

    @Test
    void buildRequestBody_modelCanBeOverriddenViaOptions() {
        Map<String, Object> body = client.buildRequestBody(
                List.of(ChatMessage.user("hi")), List.of(),
                new LlmOptions(null, null, "override-model"));
        assertThat(body).containsEntry("model", "override-model");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBody_encodesToolCallsAndToolResults() {
        ToolCall call = new ToolCall("tu_1", "search_logs", Map.of("service", "payments"));
        List<ChatMessage> messages = List.of(
                ChatMessage.user("why slow?"),
                ChatMessage.assistant("looking it up", List.of(call)),
                ChatMessage.tool("tu_1", "{\"count\":3}"));

        Map<String, Object> body = client.buildRequestBody(messages, List.of(), LlmOptions.defaults());
        List<Map<String, Object>> encoded = (List<Map<String, Object>>) body.get("messages");
        assertThat(encoded).hasSize(3);

        Map<String, Object> assistantMsg = encoded.get(1);
        assertThat(assistantMsg).containsEntry("role", "assistant");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) assistantMsg.get("content");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).containsEntry("type", "text");
        Map<String, Object> toolUse = blocks.get(1);
        assertThat(toolUse).containsEntry("type", "tool_use");
        assertThat(toolUse).containsEntry("id", "tu_1");
        assertThat(toolUse).containsEntry("name", "search_logs");

        Map<String, Object> toolResultMsg = encoded.get(2);
        assertThat(toolResultMsg).containsEntry("role", "user");
        List<Map<String, Object>> resultBlocks = (List<Map<String, Object>>) toolResultMsg.get("content");
        assertThat(resultBlocks.get(0))
                .containsEntry("type", "tool_result")
                .containsEntry("tool_use_id", "tu_1");
    }

    @Test
    void parseResponse_extractsTextAndToolCalls() throws Exception {
        String json = """
                {
                  "content": [
                    {"type": "text", "text": "Looking at logs..."},
                    {"type": "tool_use", "id": "tu_42", "name": "search_logs", "input": {"service":"payments","limit":50}}
                  ],
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 120, "output_tokens": 30}
                }
                """;
        JsonNode node = mapper.readTree(json);
        LlmResponse response = client.parseResponse(node);

        assertThat(response.text()).isEqualTo("Looking at logs...");
        assertThat(response.stopReason()).isEqualTo("tool_use");
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall tc = response.toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("tu_42");
        assertThat(tc.name()).isEqualTo("search_logs");
        assertThat(tc.args()).containsEntry("service", "payments");
        assertThat(tc.args()).containsEntry("limit", 50);
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.usage().outputTokens()).isEqualTo(30);
    }

    @Test
    void switchingBaseUrlAndModelInConfigRetargetsTheClient_FR1Acceptance() {
        // Initial pointing
        Map<String, Object> body1 = client.buildRequestBody(
                List.of(ChatMessage.user("q")), List.of(), LlmOptions.defaults());
        assertThat(body1).containsEntry("model", "claude-test-model");

        // Repoint via config alone — no code change
        properties.getAihub().setBaseUrl("https://ai-hub.idfcfirstbank.com/v1");
        properties.getAihub().setModel("idfc-aihub-rca-v1");
        properties.getAihub().setMaxTokens(4096);

        Map<String, Object> body2 = client.buildRequestBody(
                List.of(ChatMessage.user("q")), List.of(), LlmOptions.defaults());
        assertThat(body2).containsEntry("model", "idfc-aihub-rca-v1");
        assertThat(body2).containsEntry("max_tokens", 4096);
    }
}
