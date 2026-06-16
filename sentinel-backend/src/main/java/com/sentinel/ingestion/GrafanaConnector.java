package com.sentinel.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests infrastructure metrics from Grafana HTTP API.
 * Queries CPU, memory, pod status, HTTP error rates, and latency percentiles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrafanaConnector {

    private final WebClient grafanaWebClient;
    private final SentinelProperties properties;

    // Prometheus query expressions
    private static final String CPU_QUERY = "rate(container_cpu_usage_seconds_total[5m]) * 100";
    private static final String MEMORY_QUERY = "container_memory_usage_bytes / container_spec_memory_limit_bytes * 100";
    private static final String ERROR_RATE_QUERY = "rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]) / rate(http_server_requests_seconds_count[5m]) * 100";
    private static final String LATENCY_P95_QUERY = "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) * 1000";
    private static final String LATENCY_P99_QUERY = "histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) * 1000";
    private static final String POD_RESTART_QUERY = "increase(kube_pod_container_status_restarts_total[1h])";
    private static final String NETWORK_IO_QUERY = "rate(container_network_transmit_bytes_total[5m])";

    /**
     * Fetches all critical metrics from Grafana's Prometheus datasource.
     */
    public List<NormalizedEvent> fetchMetrics() {
        List<NormalizedEvent> events = new ArrayList<>();

        events.addAll(fetchAndNormalize(CPU_QUERY, "cpu_percent", 90.0, "CPU usage critical"));
        events.addAll(fetchAndNormalize(MEMORY_QUERY, "memory_percent", 85.0, "Memory usage high"));
        events.addAll(fetchAndNormalize(ERROR_RATE_QUERY, "error_rate_percent", 5.0, "HTTP error rate elevated"));
        events.addAll(fetchLatencyMetrics());
        events.addAll(fetchPodRestartMetrics());
        events.addAll(fetchNetworkMetrics());

        return events;
    }

    /**
     * Returns the current metric value for a specific service and metric name.
     * Used by the anomaly detection service.
     */
    public Map<String, Double> fetchCurrentMetricValues(String prometheusQuery) {
        Map<String, Double> values = new HashMap<>();
        JsonNode result = queryPrometheus(prometheusQuery);
        if (result == null) return values;

        for (JsonNode item : result.path("data").path("result")) {
            String service = extractServiceLabel(item);
            double value = item.path("value").get(1).asDouble(0);
            values.put(service, value);
        }
        return values;
    }

    private List<NormalizedEvent> fetchAndNormalize(String promQuery, String metricName,
                                                     double threshold, String description) {
        List<NormalizedEvent> events = new ArrayList<>();
        JsonNode result = queryPrometheus(promQuery);
        if (result == null) return events;

        for (JsonNode item : result.path("data").path("result")) {
            String service = extractServiceLabel(item);
            double value = item.path("value").get(1).asDouble(0);

            if (value > threshold) {
                Severity severity = determineSeverity(metricName, value);
                String message = String.format("%s for %s: %.2f%%", description, service, value);

                Map<String, Object> attrs = new HashMap<>();
                attrs.put("metric_name", metricName);
                attrs.put("metric_value", value);
                attrs.put("threshold", threshold);
                attrs.put("prometheus_query", promQuery);

                // Tag specific conditions for correlation rules
                if ("cpu_percent".equals(metricName) && value > 90) {
                    attrs.put("high_cpu", true);
                }
                if ("memory_percent".equals(metricName) && value > 85) {
                    attrs.put("high_memory", true);
                }
                if ("error_rate_percent".equals(metricName) && value > 5) {
                    attrs.put("high_error_rate", true);
                    attrs.put("5xx_spike", value > 50);
                }

                events.add(NormalizedEvent.ofMetric(service, severity, message, attrs));
            }
        }
        return events;
    }

    private List<NormalizedEvent> fetchLatencyMetrics() {
        List<NormalizedEvent> events = new ArrayList<>();
        JsonNode p95Result = queryPrometheus(LATENCY_P95_QUERY);
        JsonNode p99Result = queryPrometheus(LATENCY_P99_QUERY);

        Map<String, Double> p95Map = parseMetricValues(p95Result);
        Map<String, Double> p99Map = parseMetricValues(p99Result);

        for (Map.Entry<String, Double> entry : p95Map.entrySet()) {
            String service = entry.getKey();
            double p95 = entry.getValue();
            double p99 = p99Map.getOrDefault(service, p95);

            if (p95 > 1000 || p99 > 2000) { // >1s P95 or >2s P99
                Severity severity = p99 > 5000 ? Severity.P2_HIGH : Severity.P3_MEDIUM;
                String message = String.format("High latency for %s: P95=%.0fms P99=%.0fms", service, p95, p99);

                Map<String, Object> attrs = new HashMap<>();
                attrs.put("metric_name", "latency_ms");
                attrs.put("latency_p95_ms", p95);
                attrs.put("latency_p99_ms", p99);
                attrs.put("latency_degradation", p99 > 2000);
                attrs.put("network_io_spike", false); // updated by network check

                events.add(NormalizedEvent.ofMetric(service, severity, message, attrs));
            }
        }
        return events;
    }

    private List<NormalizedEvent> fetchPodRestartMetrics() {
        List<NormalizedEvent> events = new ArrayList<>();
        JsonNode result = queryPrometheus(POD_RESTART_QUERY);
        if (result == null) return events;

        for (JsonNode item : result.path("data").path("result")) {
            String service = extractServiceLabel(item);
            double restarts = item.path("value").get(1).asDouble(0);

            if (restarts >= 3) {
                Severity severity = restarts >= 5 ? Severity.P1_CRITICAL : Severity.P2_HIGH;
                String message = String.format("Pod restart count %d for %s in last hour",
                        (int) restarts, service);

                Map<String, Object> attrs = new HashMap<>();
                attrs.put("metric_name", "pod_restarts");
                attrs.put("restart_count", restarts);
                attrs.put("crash_loop_suspected", restarts >= 3);

                events.add(NormalizedEvent.ofMetric(service, severity, message, attrs));
            }
        }
        return events;
    }

    private List<NormalizedEvent> fetchNetworkMetrics() {
        List<NormalizedEvent> events = new ArrayList<>();
        JsonNode result = queryPrometheus(NETWORK_IO_QUERY);
        if (result == null) return events;

        // Network I/O is added as attributes but only flagged if very high
        for (JsonNode item : result.path("data").path("result")) {
            String service = extractServiceLabel(item);
            double bytesPerSec = item.path("value").get(1).asDouble(0);

            if (bytesPerSec > 100 * 1024 * 1024) { // >100MB/s
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("metric_name", "network_io_bytes_per_sec");
                attrs.put("network_io_bytes_per_sec", bytesPerSec);
                attrs.put("network_io_spike", true);

                events.add(NormalizedEvent.ofMetric(service, Severity.P3_MEDIUM,
                        String.format("High network I/O for %s: %.1f MB/s", service, bytesPerSec / (1024 * 1024)),
                        attrs));
            }
        }
        return events;
    }

    private JsonNode queryPrometheus(String query) {
        try {
            return grafanaWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/datasources/proxy/1/api/v1/query")
                            .queryParam("query", query)
                            .queryParam("time", Instant.now().getEpochSecond())
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.debug("Prometheus query failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractServiceLabel(JsonNode item) {
        JsonNode metric = item.path("metric");
        // Try common label names
        for (String label : List.of("service", "app", "container", "job")) {
            String val = metric.path(label).asText("");
            if (!val.isEmpty() && !"".equals(val)) return val;
        }
        return "unknown";
    }

    private Map<String, Double> parseMetricValues(JsonNode result) {
        Map<String, Double> values = new HashMap<>();
        if (result == null) return values;
        for (JsonNode item : result.path("data").path("result")) {
            values.put(extractServiceLabel(item), item.path("value").get(1).asDouble(0));
        }
        return values;
    }

    private Severity determineSeverity(String metricName, double value) {
        return switch (metricName) {
            case "cpu_percent" -> value > 95 ? Severity.P1_CRITICAL : Severity.P2_HIGH;
            case "memory_percent" -> value > 95 ? Severity.P1_CRITICAL : Severity.P2_HIGH;
            case "error_rate_percent" -> value > 50 ? Severity.P1_CRITICAL :
                    value > 10 ? Severity.P2_HIGH : Severity.P3_MEDIUM;
            default -> Severity.P3_MEDIUM;
        };
    }
}
