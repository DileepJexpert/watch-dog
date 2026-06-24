package com.sentinel.ingestion;

import com.sentinel.config.KafkaConfig;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.normalization.EventNormalizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
public class IngestionScheduler {

    private final ElasticsearchConnector elasticsearchConnector;
    private final JaegerConnector jaegerConnector;
    private final GrafanaConnector grafanaConnector;
    private final HealthProbeService healthProbeService;
    private final EventNormalizationService normalizationService;
    private final KafkaTemplate<String, NormalizedEvent> kafkaTemplate;
    private final ObjectProvider<KafkaConnector> kafkaConnectorProvider;
    private final ObjectProvider<ActiveMqConnector> activeMqConnectorProvider;

    public IngestionScheduler(ElasticsearchConnector elasticsearchConnector,
                              JaegerConnector jaegerConnector,
                              GrafanaConnector grafanaConnector,
                              HealthProbeService healthProbeService,
                              EventNormalizationService normalizationService,
                              KafkaTemplate<String, NormalizedEvent> kafkaTemplate,
                              ObjectProvider<KafkaConnector> kafkaConnectorProvider,
                              ObjectProvider<ActiveMqConnector> activeMqConnectorProvider) {
        this.elasticsearchConnector = elasticsearchConnector;
        this.jaegerConnector = jaegerConnector;
        this.grafanaConnector = grafanaConnector;
        this.healthProbeService = healthProbeService;
        this.normalizationService = normalizationService;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaConnectorProvider = kafkaConnectorProvider;
        this.activeMqConnectorProvider = activeMqConnectorProvider;
    }

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

    /**
     * Poll Kafka AdminClient for per-consumer-group lag.
     * No-op unless {@code sentinel.kafka.enabled=true} — the bean is
     * conditional, so the provider returns null when disabled.
     */
    @Scheduled(fixedDelayString = "${sentinel.kafka.poll-interval-seconds:30}000")
    public void pollKafkaAdmin() {
        KafkaConnector connector = kafkaConnectorProvider.getIfAvailable();
        if (connector == null) return;
        try {
            List<NormalizedEvent> events = connector.fetchMetrics();
            publish(events, "kafka-admin");
        } catch (Exception e) {
            log.error("Kafka admin ingestion failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Poll ActiveMQ via Jolokia for queue depths.
     * No-op unless {@code sentinel.activemq.enabled=true}.
     */
    @Scheduled(fixedDelayString = "${sentinel.activemq.poll-interval-seconds:30}000")
    public void pollActiveMq() {
        ActiveMqConnector connector = activeMqConnectorProvider.getIfAvailable();
        if (connector == null) return;
        try {
            List<NormalizedEvent> events = connector.fetchMetrics();
            publish(events, "activemq");
        } catch (Exception e) {
            log.error("ActiveMQ ingestion failed: {}", e.getMessage(), e);
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
