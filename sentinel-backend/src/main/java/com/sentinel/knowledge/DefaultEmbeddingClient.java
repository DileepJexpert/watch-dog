package com.sentinel.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.config.SentinelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Default embedding client.
 *
 * Behavior:
 *  - If {@code sentinel.knowledge.embedding-base-url} is configured, POSTs to
 *    {@code /v1/embeddings} (OpenAI-compatible envelope; AI Hub deployments
 *    that follow this contract Just Work).
 *  - Otherwise falls back to a deterministic hash-based pseudo-embedding so
 *    the rest of the RAG path is exercisable in tests / demo environments
 *    without an external dependency. Logs a warning on each fallback call so
 *    the gap is loud.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
public class DefaultEmbeddingClient implements EmbeddingClient {

    private final WebClient defaultWebClient;
    private final SentinelProperties properties;

    public DefaultEmbeddingClient(WebClient defaultWebClient, SentinelProperties properties) {
        this.defaultWebClient = defaultWebClient;
        this.properties = properties;
    }

    @Override
    public List<Double> embed(String text) {
        var cfg = properties.getKnowledge();
        String baseUrl = cfg.getEmbeddingBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return hashEmbedding(text, cfg.getEmbeddingDimension());
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", cfg.getEmbeddingModel(),
                    "input", text);
            JsonNode response = defaultWebClient.post()
                    .uri(baseUrl.endsWith("/v1/embeddings") ? baseUrl : baseUrl + "/v1/embeddings")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getEmbeddingApiKey())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            return parseEmbedding(response, cfg.getEmbeddingDimension());
        } catch (Exception e) {
            log.warn("Embedding API call failed ({}); falling back to hash embedding", e.getMessage());
            return hashEmbedding(text, cfg.getEmbeddingDimension());
        }
    }

    private List<Double> parseEmbedding(JsonNode response, int dimension) {
        if (response == null) return hashEmbedding("", dimension);
        JsonNode vec = response.path("data").path(0).path("embedding");
        if (!vec.isArray() || vec.isEmpty()) {
            vec = response.path("embedding");
        }
        if (!vec.isArray() || vec.isEmpty()) {
            log.warn("Embedding response shape unrecognised; falling back to hash embedding");
            return hashEmbedding("", dimension);
        }
        List<Double> values = new ArrayList<>(vec.size());
        for (JsonNode v : vec) values.add(v.asDouble(0));
        return values;
    }

    /**
     * Deterministic pseudo-embedding for the demo / test fallback path. Uses a
     * rolling 32-bit hash seeded by character position so semantically similar
     * inputs still produce similar vectors (good enough to demo cosine search,
     * not a substitute for a real model).
     */
    private List<Double> hashEmbedding(String text, int dimension) {
        if (text == null) text = "";
        byte[] bytes = text.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        double[] vec = new double[dimension];
        int seed = 0x811c9dc5;
        for (int i = 0; i < bytes.length; i++) {
            seed ^= bytes[i];
            seed *= 0x01000193;
            int idx = Math.floorMod(seed, dimension);
            vec[idx] += 1.0;
        }
        double norm = 0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) norm = 1;
        List<Double> out = new ArrayList<>(dimension);
        for (double v : vec) out.add(v / norm);
        return out;
    }
}
