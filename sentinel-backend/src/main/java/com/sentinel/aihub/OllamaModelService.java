package com.sentinel.aihub;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.config.SentinelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists locally-installed Ollama models so the UI can render a dropdown.
 *
 * Only relevant when {@code sentinel.aihub.provider=ollama}. Calls Ollama's
 * /api/tags endpoint, which returns models pulled via {@code ollama pull <name>}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaModelService {

    private final WebClient llmWebClient;
    private final SentinelProperties properties;

    public List<Map<String, Object>> listInstalled() {
        if (!"ollama".equalsIgnoreCase(properties.getAihub().getProvider())) {
            log.debug("[ollama/models] provider={} != ollama, returning empty list", properties.getAihub().getProvider());
            return List.of();
        }
        long startNs = System.nanoTime();
        log.debug("[ollama/models] → GET {}/api/tags", properties.getAihub().getBaseUrl());
        try {
            JsonNode response = llmWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            List<Map<String, Object>> out = new ArrayList<>();
            if (response == null) return out;
            JsonNode models = response.path("models");
            if (!models.isArray()) return out;

            for (JsonNode m : models) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", m.path("name").asText());
                entry.put("size", m.path("size").asLong(0));
                entry.put("modified", m.path("modified_at").asText(""));
                JsonNode details = m.path("details");
                if (details.isObject()) {
                    entry.put("family", details.path("family").asText(""));
                    entry.put("parameterSize", details.path("parameter_size").asText(""));
                    entry.put("quantization", details.path("quantization_level").asText(""));
                }
                out.add(entry);
            }
            log.info("[ollama/models] ← {} model(s) in {}ms: {}",
                    out.size(), (System.nanoTime() - startNs) / 1_000_000,
                    out.stream().map(e -> e.get("name")).toList());
            return out;
        } catch (Exception e) {
            log.warn("[ollama/models] FAILED in {}ms: {}",
                    (System.nanoTime() - startNs) / 1_000_000, e.getMessage());
            return List.of();
        }
    }

    /**
     * Switch the active model. Both LlmClient impls consult
     * {@code properties.getAihub().getModel()} on every call, so mutating it
     * here takes effect on the next agent run — no rebean / restart needed.
     */
    public void setActiveModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("model name is required");
        }
        log.info("Switching active LLM model: {} → {}",
                properties.getAihub().getModel(), modelName);
        properties.getAihub().setModel(modelName);
    }

    public String getActiveModel() {
        return properties.getAihub().getModel();
    }
}
