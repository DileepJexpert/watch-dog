package com.sentinel.ingestion;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * First-class Kafka monitoring connector.
 *
 * Polls AdminClient for:
 *   - Broker availability (describeCluster)
 *   - Per-consumer-group lag, summed across partitions, per service
 *
 * Emits NormalizedEvents in the shape MessageQueueBacklogRule already consumes:
 *   signalType = METRIC
 *   attributes = {metric_name=consumer_lag, metric_value=<long>, consumer_group=<id>, topic=<list>}
 *
 * Only registers when {@code sentinel.kafka.enabled=true}. AdminClient creation
 * is lazy and reused across polls; the @PreDestroy hook closes it cleanly.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "sentinel.kafka", name = "enabled", havingValue = "true")
public class KafkaConnector {

    private final SentinelProperties properties;
    private volatile AdminClient adminClient;

    public KafkaConnector(SentinelProperties properties) {
        this.properties = properties;
    }

    public List<NormalizedEvent> fetchMetrics() {
        SentinelProperties.KafkaIngestConfig cfg = properties.getKafka();
        if (!cfg.isEnabled()) return List.of();

        AdminClient admin = client(cfg);
        if (admin == null) {
            return List.of(brokerUnavailableEvent("unable to construct AdminClient"));
        }

        List<NormalizedEvent> events = new ArrayList<>();

        if (!isBrokerHealthy(admin, cfg)) {
            events.add(brokerUnavailableEvent("describeCluster timed out / no nodes"));
            return events;
        }

        if (cfg.getConsumerGroups() == null || cfg.getConsumerGroups().isEmpty()) {
            log.debug("Kafka connector: no consumer-group mappings configured");
            return events;
        }

        for (Map.Entry<String, String> entry : cfg.getConsumerGroups().entrySet()) {
            String serviceName = entry.getKey();
            String groupId = entry.getValue();
            try {
                NormalizedEvent lagEvent = measureLag(admin, serviceName, groupId, cfg);
                if (lagEvent != null) events.add(lagEvent);
            } catch (Exception e) {
                log.warn("Failed to measure lag for group {} (service={}): {}",
                        groupId, serviceName, e.getMessage());
            }
        }
        return events;
    }

    private NormalizedEvent measureLag(AdminClient admin, String serviceName,
                                       String groupId, SentinelProperties.KafkaIngestConfig cfg)
            throws Exception {

        ListConsumerGroupOffsetsResult offsetsResult = admin.listConsumerGroupOffsets(groupId);
        Map<TopicPartition, OffsetAndMetadata> committed = offsetsResult
                .partitionsToOffsetAndMetadata()
                .get(cfg.getAdminTimeoutMs(), TimeUnit.MILLISECONDS);

        if (committed == null || committed.isEmpty()) {
            log.debug("Group {} has no committed offsets — skipping", groupId);
            return null;
        }

        // Optional topic filter
        List<String> watchTopics = cfg.getTopics();
        if (watchTopics != null && !watchTopics.isEmpty()) {
            committed = committed.entrySet().stream()
                    .filter(e -> watchTopics.contains(e.getKey().topic()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (committed.isEmpty()) return null;
        }

        Map<TopicPartition, OffsetSpec> latestSpec = new HashMap<>();
        for (TopicPartition tp : committed.keySet()) latestSpec.put(tp, OffsetSpec.latest());

        ListOffsetsResult latestResult = admin.listOffsets(latestSpec);
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                latestResult.all().get(cfg.getAdminTimeoutMs(), TimeUnit.MILLISECONDS);

        long totalLag = 0;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
            ListOffsetsResult.ListOffsetsResultInfo end = latest.get(e.getKey());
            if (end == null) continue;
            long lag = end.offset() - e.getValue().offset();
            if (lag > 0) totalLag += lag;
        }

        List<String> topics = committed.keySet().stream()
                .map(TopicPartition::topic).distinct().toList();

        Severity severity = totalLag > 100_000 ? Severity.P1_CRITICAL
                : totalLag > 10_000 ? Severity.P2_HIGH
                : Severity.P4_INFO;

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("metric_name", "consumer_lag");
        attrs.put("metric_value", totalLag);
        attrs.put("consumer_group", groupId);
        attrs.put("topics", topics);
        attrs.put("source", "kafka-admin");

        log.debug("Kafka lag — service={} group={} totalLag={} topics={}",
                serviceName, groupId, totalLag, topics);

        return NormalizedEvent.ofMetric(serviceName, severity,
                String.format("Consumer lag for group %s: %d messages", groupId, totalLag),
                attrs);
    }

    private boolean isBrokerHealthy(AdminClient admin, SentinelProperties.KafkaIngestConfig cfg) {
        try {
            DescribeClusterResult describe = admin.describeCluster();
            int nodes = describe.nodes().get(cfg.getAdminTimeoutMs(), TimeUnit.MILLISECONDS).size();
            return nodes > 0;
        } catch (Exception e) {
            log.warn("Kafka describeCluster failed: {}", e.getMessage());
            return false;
        }
    }

    private NormalizedEvent brokerUnavailableEvent(String detail) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("metric_name", "broker_available");
        attrs.put("metric_value", 0);
        attrs.put("source", "kafka-admin");
        attrs.put("reason", detail);
        return NormalizedEvent.ofMetric("kafka-broker", Severity.P1_CRITICAL,
                "Kafka broker unreachable: " + detail, attrs);
    }

    private AdminClient client(SentinelProperties.KafkaIngestConfig cfg) {
        AdminClient existing = adminClient;
        if (existing != null) return existing;
        synchronized (this) {
            if (adminClient == null) {
                try {
                    Properties props = new Properties();
                    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getBootstrapServers());
                    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, cfg.getAdminTimeoutMs());
                    props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, cfg.getAdminTimeoutMs());
                    adminClient = AdminClient.create(props);
                    log.info("Kafka AdminClient created for {}", cfg.getBootstrapServers());
                } catch (Exception e) {
                    log.error("Failed to create Kafka AdminClient: {}", e.getMessage());
                    return null;
                }
            }
            return adminClient;
        }
    }

    @PreDestroy
    void shutdown() {
        if (adminClient != null) {
            try {
                adminClient.close(Duration.ofSeconds(2));
            } catch (Exception ignored) {}
        }
    }
}
