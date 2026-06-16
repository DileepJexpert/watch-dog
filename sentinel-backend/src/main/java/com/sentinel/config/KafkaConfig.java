package com.sentinel.config;

import com.sentinel.model.NormalizedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String EVENTS_TOPIC = "sentinel.normalized-events";
    public static final String INCIDENTS_TOPIC = "sentinel.incidents";
    public static final String REMEDIATION_TOPIC = "sentinel.remediation-actions";

    @Bean
    public NewTopic normalizedEventsTopic() {
        return TopicBuilder.name(EVENTS_TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic incidentsTopic() {
        return TopicBuilder.name(INCIDENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic remediationTopic() {
        return TopicBuilder.name(REMEDIATION_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
