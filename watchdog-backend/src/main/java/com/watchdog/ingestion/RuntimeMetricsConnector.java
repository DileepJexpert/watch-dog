package com.watchdog.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.watchdog.config.WatchdogProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * FR-5: APM / runtime metrics — thread pool usage, transaction peaks, CPU, RAM.
 *
 * Default source is Spring Boot Actuator / Micrometer:
 *   /actuator/metrics                 — name index
 *   /actuator/metrics/{name}?tag=...  — current value
 *
 * Each target service is reached by base URL. If a central APM_URL is configured
 * the connector falls back to it (e.g. for non-Spring services).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
public class RuntimeMetricsConnector {

    private static final List<String> CORE_METRICS = List.of(
            "system.cpu.usage",
            "process.cpu.usage",
            "jvm.memory.used",
            "jvm.memory.max",
            "jvm.threads.live",
            "jvm.threads.peak",
            "executor.active",
            "executor.pool.size",
            "executor.queued",
            "http.server.requests"
    );

    private final WebClient defaultWebClient;
    private final WatchdogProperties properties;

    public RuntimeMetricsConnector(WebClient defaultWebClient, WatchdogProperties properties) {
        this.defaultWebClient = defaultWebClient;
        this.properties = properties;
    }

    public Map<String, Object> fetchRuntimeMetrics(String serviceBaseUrl) {
        String base = resolveBaseUrl(serviceBaseUrl);
        if (base == null) {
            return Map.of("error", "no runtime metrics source configured; set APM_URL or pass a service URL");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", base);
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String metricName : CORE_METRICS) {
            JsonNode node = safeFetch(base, "/actuator/metrics/" + metricName);
            if (node == null) continue;
            Map<String, Object> snapshot = summarize(node);
            if (!snapshot.isEmpty()) metrics.put(metricName, snapshot);
        }
        out.put("metrics", metrics);
        return out;
    }

    private String resolveBaseUrl(String requested) {
        if (requested != null && !requested.isBlank()) return stripTrailingSlash(requested);
        String apm = properties.getApm().getUrl();
        return (apm == null || apm.isBlank()) ? null : stripTrailingSlash(apm);
    }

    private String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private JsonNode safeFetch(String base, String path) {
        try {
            return defaultWebClient.get()
                    .uri(base + path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.debug("runtime metrics fetch failed for {}{}: {}", base, path, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> summarize(JsonNode metricNode) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode measurements = metricNode.path("measurements");
        if (measurements.isArray()) {
            for (JsonNode m : measurements) {
                String stat = m.path("statistic").asText();
                double value = m.path("value").asDouble(0);
                out.put(stat.toLowerCase(Locale.ROOT), value);
            }
        }
        JsonNode tags = metricNode.path("availableTags");
        if (tags.isArray() && tags.size() > 0) {
            List<Map<String, Object>> tagsOut = new ArrayList<>();
            for (JsonNode tag : tags) {
                Map<String, Object> tagMap = new LinkedHashMap<>();
                tagMap.put("tag", tag.path("tag").asText());
                List<String> values = new ArrayList<>();
                tag.path("values").forEach(v -> values.add(v.asText()));
                tagMap.put("values", values);
                tagsOut.add(tagMap);
            }
            out.put("tags", tagsOut);
        }
        return out;
    }
}
