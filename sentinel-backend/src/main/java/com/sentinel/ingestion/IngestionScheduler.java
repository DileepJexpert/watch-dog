package com.sentinel.ingestion;

import com.sentinel.config.KafkaConfig;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.normalization.EventNormalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled polling coordinator for all data sources.
 * Runs each connector at its configured interval and publishes
 * normalized events to the Kafka topic for downstream processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionScheduler {

    private final ElasticsearchConnector elasticsearchConnector;
    private final JaegerConnector jaegerConnector;
    private final GrafanaConnector grafanaConnector;
    private final HealthProbeService healthProbeService;
    private final EventNormalizationService normalizationService;
    private final KafkaTemplate<String, NormalizedEvent> kafkaTemplate;

    /** Poll Grafana every 15 seconds */
    @Scheduled(fixedDelayString = "${sentinel.grafana.poll-interval-seconds:15}000")
    public void pollGrafana() {
        try {
            List<NormalizedEvent> events = grafanaConnector.fetchMetrics();
            publish(events, "grafana");
        } catch (Exception e) {
            log.error("Grafana ingestion failed: {}", e.getMessage(), e);
        }
    }

    /** Poll Elasticsearch every 30 seconds */
    @Scheduled(fixedDelayString = "${sentinel.elasticsearch.poll-interval-seconds:30}000")
    public void pollElasticsearch() {
        try {
            List<NormalizedEvent> events = elasticsearchConnector.fetchRecentErrorLogs();
            publish(events, "elasticsearch");
        } catch (Exception e) {
            log.error("Elasticsearch ingestion failed: {}", e.getMessage(), e);
        }
    }

    /** Poll Jaeger every 60 seconds */
    @Scheduled(fixedDelayString = "${sentinel.jaeger.poll-interval-seconds:60}000")
    public void pollJaeger() {
        try {
            List<NormalizedEvent> events = jaegerConnector.fetchSlowAndErrorTraces();
            publish(events, "jaeger");
        } catch (Exception e) {
            log.error("Jaeger ingestion failed: {}", e.getMessage(), e);
        }
    }

    /** Active health probes every 10 seconds */
    @Scheduled(fixedDelayString = "${sentinel.health-probe.interval-seconds:10}000")
    public void runHealthProbes() {
        try {
            List<NormalizedEvent> events = healthProbeService.probeAll();
            publish(events, "health-probe");
        } catch (Exception e) {
            log.error("Health probe ingestion failed: {}", e.getMessage(), e);
        }
    }

    private void publish(List<NormalizedEvent> events, String source) {
        if (events.isEmpty()) return;

        log.debug("Publishing {} events from {}", events.size(), source);
        for (NormalizedEvent event : events) {
            kafkaTemplate.send(KafkaConfig.EVENTS_TOPIC, event.serviceName(), event);
        }
    }
}
