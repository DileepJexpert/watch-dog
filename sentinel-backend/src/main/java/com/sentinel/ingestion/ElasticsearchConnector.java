package com.sentinel.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests log events from Elasticsearch (Kibana backend).
 * Polls the _search API every 30 seconds for ERROR/WARN log entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchConnector {

    private final WebClient elasticsearchWebClient;
    private final SentinelProperties properties;

    /**
     * Fetches recent error/warn log entries from Elasticsearch for all services.
     * Returns normalized events for further processing.
     */
    public List<NormalizedEvent> fetchRecentErrorLogs() {
        String indexPattern = properties.getElasticsearch().getIndexPattern();
        Instant since = Instant.now().minus(properties.getElasticsearch().getPollIntervalSeconds() + 5, ChronoUnit.SECONDS);

        String query = buildErrorLogQuery(since.toEpochMilli());

        try {
            JsonNode response = elasticsearchWebClient.post()
                    .uri("/" + indexPattern + "/_search")
                    .header("Content-Type", "application/json")
                    .bodyValue(query)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return parseSearchResponse(response);
        } catch (Exception e) {
            log.warn("Failed to fetch logs from Elasticsearch: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches error count aggregations per service for anomaly detection.
     */
    public Map<String, Long> fetchErrorCountsByService() {
        String indexPattern = properties.getElasticsearch().getIndexPattern();
        Instant since = Instant.now().minus(5, ChronoUnit.MINUTES);

        String query = buildAggregationQuery(since.toEpochMilli());

        try {
            JsonNode response = elasticsearchWebClient.post()
                    .uri("/" + indexPattern + "/_search")
                    .header("Content-Type", "application/json")
                    .bodyValue(query)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return parseAggregationResponse(response);
        } catch (Exception e) {
            log.warn("Failed to fetch error counts from Elasticsearch: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildErrorLogQuery(long fromMs) {
        return """
                {
                  "query": {
                    "bool": {
                      "must": [
                        {"terms": {"log.level": ["ERROR", "WARN", "FATAL"]}},
                        {"range": {"@timestamp": {"gte": %d, "format": "epoch_millis"}}}
                      ]
                    }
                  },
                  "size": 500,
                  "sort": [{"@timestamp": {"order": "desc"}}],
                  "_source": ["@timestamp", "service.name", "log.level", "message", "error.message", "error.type"]
                }
                """.formatted(fromMs);
    }

    private String buildAggregationQuery(long fromMs) {
        return """
                {
                  "query": {
                    "bool": {
                      "must": [
                        {"term": {"log.level": "ERROR"}},
                        {"range": {"@timestamp": {"gte": %d, "format": "epoch_millis"}}}
                      ]
                    }
                  },
                  "size": 0,
                  "aggs": {
                    "by_service": {
                      "terms": {"field": "service.name.keyword", "size": 100},
                      "aggs": {"error_count": {"value_count": {"field": "_id"}}}
                    }
                  }
                }
                """.formatted(fromMs);
    }

    private List<NormalizedEvent> parseSearchResponse(JsonNode response) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (response == null) return events;

        JsonNode hits = response.path("hits").path("hits");
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            String serviceName = source.path("service").path("name").asText("unknown");
            String level = source.path("log").path("level").asText("INFO");
            String message = source.path("message").asText("");
            String errorType = source.path("error").path("type").asText("");

            Severity severity = mapLogLevelToSeverity(level);
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("log_level", level);
            attrs.put("error_type", errorType);
            attrs.put("index", hit.path("_index").asText());
            attrs.put("es_id", hit.path("_id").asText());

            String errorMsg = source.path("error").path("message").asText();
            if (!errorMsg.isEmpty()) {
                attrs.put("error_message", errorMsg);
            }

            // Detect OOM patterns
            if (message.contains("OutOfMemoryError") || message.contains("OOM") ||
                    errorMsg.contains("OutOfMemoryError")) {
                attrs.put("oom_detected", true);
            }

            // Detect DB connection errors
            if (message.contains("Connection refused") || message.contains("connection pool") ||
                    message.contains("JDBC") || errorMsg.contains("connection")) {
                attrs.put("db_connection_error", true);
            }

            // Detect retry storms
            if (message.contains("retry") || message.contains("Retry") || message.contains("retrying")) {
                attrs.put("retry_detected", true);
            }

            events.add(NormalizedEvent.ofLog(serviceName, severity, message, attrs));
        }
        return events;
    }

    private Map<String, Long> parseAggregationResponse(JsonNode response) {
        Map<String, Long> counts = new HashMap<>();
        if (response == null) return counts;

        JsonNode buckets = response.path("aggregations").path("by_service").path("buckets");
        for (JsonNode bucket : buckets) {
            String service = bucket.path("key").asText();
            long count = bucket.path("error_count").path("value").asLong(0);
            counts.put(service, count);
        }
        return counts;
    }

    /**
     * Fetches the most recent error stack trace for a service from Elasticsearch.
     * Returns the top {@code maxLines} lines, or an empty string if none found or on error.
     */
    public String fetchLatestStackTrace(String serviceName, int maxLines) {
        String indexPattern = properties.getElasticsearch().getIndexPattern();
        String query = """
                {
                  "query": {
                    "bool": {
                      "must": [
                        {"term":  {"log.level": "ERROR"}},
                        {"term":  {"service.name.keyword": "%s"}},
                        {"exists": {"field": "error.stack_trace"}}
                      ]
                    }
                  },
                  "size": 1,
                  "sort": [{"@timestamp": {"order": "desc"}}],
                  "_source": ["error.stack_trace", "message"]
                }
                """.formatted(serviceName);

        try {
            JsonNode response = elasticsearchWebClient.post()
                    .uri("/" + indexPattern + "/_search")
                    .header("Content-Type", "application/json")
                    .bodyValue(query)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null) return "";
            JsonNode hits = response.path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) return "";

            JsonNode source = hits.get(0).path("_source");
            String stackTrace = source.path("error").path("stack_trace").asText("");
            if (stackTrace.isBlank()) {
                stackTrace = source.path("message").asText("");
            }

            String[] lines = stackTrace.split("\n");
            int limit = Math.min(maxLines, lines.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("Could not fetch stack trace for {}: {}", serviceName, e.getMessage());
            return "";
        }
    }

    private Severity mapLogLevelToSeverity(String level) {
        return switch (level.toUpperCase()) {
            case "FATAL", "ERROR" -> Severity.P2_HIGH;
            case "WARN" -> Severity.P3_MEDIUM;
            default -> Severity.P4_INFO;
        };
    }
}
