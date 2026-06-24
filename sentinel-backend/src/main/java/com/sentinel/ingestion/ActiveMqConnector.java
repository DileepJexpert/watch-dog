package com.sentinel.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * First-class ActiveMQ monitoring connector via the Jolokia HTTP bridge.
 *
 * Jolokia ships with the AMQ Web Console at /api/jolokia. We hit it with a
 * read-attribute request per queue ({@code QueueSize} attribute) and emit:
 *
 *   signalType = METRIC
 *   attributes = {metric_name=queue_depth, metric_value=<long>, queue=<name>, source=activemq-jolokia}
 *
 * The naming matches what MessageQueueBacklogRule expects so AMQ backlog
 * lands in the same incident pipeline as Kafka backlog.
 *
 * Only registers when {@code sentinel.activemq.enabled=true}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sentinel.activemq", name = "enabled", havingValue = "true")
public class ActiveMqConnector {

    private final SentinelProperties properties;
    private final WebClient webClient;

    public ActiveMqConnector(SentinelProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        SentinelProperties.ActiveMqConfig cfg = properties.getActivemq();
        WebClient.Builder b = builder
                .baseUrl(cfg.getJolokiaUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (cfg.getUsername() != null && !cfg.getUsername().isBlank()) {
            String creds = cfg.getUsername() + ":" + (cfg.getPassword() == null ? "" : cfg.getPassword());
            String basic = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        }
        this.webClient = b.build();
    }

    public List<NormalizedEvent> fetchMetrics() {
        SentinelProperties.ActiveMqConfig cfg = properties.getActivemq();
        if (!cfg.isEnabled()) return List.of();
        if (cfg.getQueues() == null || cfg.getQueues().isEmpty()) {
            log.debug("ActiveMQ connector: no queues configured to monitor");
            return List.of();
        }

        List<NormalizedEvent> events = new ArrayList<>();
        for (Map.Entry<String, String> entry : cfg.getQueues().entrySet()) {
            String serviceName = entry.getKey();
            String queueName = entry.getValue();
            try {
                NormalizedEvent evt = readQueueDepth(serviceName, queueName, cfg);
                if (evt != null) events.add(evt);
            } catch (Exception e) {
                log.warn("Failed to read ActiveMQ queue {}: {}", queueName, e.getMessage());
            }
        }
        return events;
    }

    private NormalizedEvent readQueueDepth(String serviceName, String queueName,
                                           SentinelProperties.ActiveMqConfig cfg) {
        // Jolokia GET form: /read/<mbean>/<attribute>
        // ActiveMQ Classic mbean: org.apache.activemq:type=Broker,brokerName=<broker>,destinationType=Queue,destinationName=<queue>
        String mbean = String.format(
                "org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=%s",
                cfg.getBrokerName(), queueName);
        String path = "/read/" + mbean + "/QueueSize";

        JsonNode response;
        try {
            response = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Jolokia read failed for queue {} (service={}): {}",
                    queueName, serviceName, e.getMessage());
            return null;
        }

        if (response == null || !response.path("value").isNumber()) {
            log.debug("Queue {} returned no QueueSize value (status={}, error={})",
                    queueName, response == null ? "?" : response.path("status").asInt(-1),
                    response == null ? "" : response.path("error").asText(""));
            return null;
        }

        long depth = response.path("value").asLong();
        Severity severity = depth > 100_000 ? Severity.P1_CRITICAL
                : depth > 10_000 ? Severity.P2_HIGH
                : Severity.P4_INFO;

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("metric_name", "queue_depth");
        attrs.put("metric_value", depth);
        attrs.put("queue", queueName);
        attrs.put("source", "activemq-jolokia");

        return NormalizedEvent.ofMetric(serviceName, severity,
                String.format("Queue depth for %s: %d pending messages", queueName, depth),
                attrs);
    }
}
