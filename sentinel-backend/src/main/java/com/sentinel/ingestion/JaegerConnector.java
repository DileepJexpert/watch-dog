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
 * Ingests distributed trace data from Jaeger Query REST API.
 * Identifies slow traces (above threshold) and error spans for correlation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JaegerConnector {

    private final WebClient jaegerWebClient;
    private final SentinelProperties properties;

    /**
     * Fetches traces exceeding the slow threshold and any error traces from the last poll window.
     */
    public List<NormalizedEvent> fetchSlowAndErrorTraces() {
        List<NormalizedEvent> events = new ArrayList<>();
        long thresholdMicros = properties.getJaeger().getSlowTraceThresholdMs() * 1000;
        Instant lookback = Instant.now().minus(
                properties.getJaeger().getPollIntervalSeconds() + 10, ChronoUnit.SECONDS);

        events.addAll(fetchServicesAndTraces(thresholdMicros, lookback));
        return events;
    }

    private List<NormalizedEvent> fetchServicesAndTraces(long thresholdMicros, Instant lookback) {
        List<NormalizedEvent> events = new ArrayList<>();

        try {
            // Fetch list of services
            JsonNode servicesResponse = jaegerWebClient.get()
                    .uri("/api/services")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (servicesResponse == null) return events;

            for (JsonNode serviceNode : servicesResponse.path("data")) {
                String service = serviceNode.asText();
                events.addAll(fetchTracesForService(service, thresholdMicros, lookback));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch services from Jaeger: {}", e.getMessage());
        }

        return events;
    }

    private List<NormalizedEvent> fetchTracesForService(String service, long thresholdMicros, Instant lookback) {
        List<NormalizedEvent> events = new ArrayList<>();
        long startMs = lookback.toEpochMilli() * 1000; // Jaeger uses microseconds

        try {
            JsonNode tracesResponse = jaegerWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/traces")
                            .queryParam("service", service)
                            .queryParam("start", startMs)
                            .queryParam("minDuration", thresholdMicros + "us")
                            .queryParam("limit", 100)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (tracesResponse != null) {
                events.addAll(parseTracesResponse(tracesResponse, service));
            }

            // Also fetch error traces regardless of duration
            JsonNode errorTracesResponse = jaegerWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/traces")
                            .queryParam("service", service)
                            .queryParam("start", startMs)
                            .queryParam("tags", "{\"error\":\"true\"}")
                            .queryParam("limit", 50)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (errorTracesResponse != null) {
                events.addAll(parseTracesResponse(errorTracesResponse, service));
            }

        } catch (Exception e) {
            log.debug("Failed to fetch traces for service {}: {}", service, e.getMessage());
        }

        return events;
    }

    private List<NormalizedEvent> parseTracesResponse(JsonNode response, String service) {
        List<NormalizedEvent> events = new ArrayList<>();
        JsonNode traces = response.path("data");

        for (JsonNode trace : traces) {
            String traceId = trace.path("traceID").asText();
            JsonNode spans = trace.path("spans");
            long totalDuration = 0;
            int errorSpanCount = 0;
            String rootOperationName = "";
            boolean hasCascadingFailure = false;

            List<String> slowServices = new ArrayList<>();

            for (JsonNode span : spans) {
                long duration = span.path("duration").asLong(0); // microseconds
                totalDuration = Math.max(totalDuration, duration);

                if (span.path("startTime").asLong(0) == trace.path("spans").get(0)
                        .path("startTime").asLong(0)) {
                    rootOperationName = span.path("operationName").asText();
                }

                // Check for error tag
                for (JsonNode tag : span.path("tags")) {
                    if ("error".equals(tag.path("key").asText()) &&
                            "true".equals(tag.path("value").asText())) {
                        errorSpanCount++;
                    }
                }

                // Detect cascading failures (multiple spans with errors)
                if (errorSpanCount > 3) {
                    hasCascadingFailure = true;
                }

                // Detect timeout chains
                String opName = span.path("operationName").asText("");
                if (opName.contains("timeout") || opName.contains("Timeout")) {
                    slowServices.add(span.path("process").path("serviceName").asText(service));
                }
            }

            Severity severity = errorSpanCount > 0 ? Severity.P2_HIGH : Severity.P3_MEDIUM;
            String message = String.format("Slow trace: %s - duration %dms, %d error spans",
                    rootOperationName, totalDuration / 1000, errorSpanCount);

            Map<String, Object> attrs = new HashMap<>();
            attrs.put("trace_id", traceId);
            attrs.put("duration_ms", totalDuration / 1000);
            attrs.put("error_span_count", errorSpanCount);
            attrs.put("root_operation", rootOperationName);
            attrs.put("cascading_failure", hasCascadingFailure);
            attrs.put("slow_services", slowServices);
            attrs.put("timeout_spans", !slowServices.isEmpty());

            events.add(NormalizedEvent.ofTrace(service, severity, message, attrs));
        }

        return events;
    }
}
