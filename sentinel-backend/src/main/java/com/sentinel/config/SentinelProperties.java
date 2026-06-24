package com.sentinel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "sentinel")
public class SentinelProperties {

    private ElasticsearchConfig elasticsearch = new ElasticsearchConfig();
    private JaegerConfig jaeger = new JaegerConfig();
    private GrafanaConfig grafana = new GrafanaConfig();
    private HealthProbeConfig healthProbe = new HealthProbeConfig();
    private CorrelationConfig correlation = new CorrelationConfig();
    private AlertingConfig alerting = new AlertingConfig();
    private RemediationConfig remediation = new RemediationConfig();
    private AnomalyConfig anomaly = new AnomalyConfig();
    private AgentConfig agent = new AgentConfig();
    private AihubConfig aihub = new AihubConfig();
    private KnowledgeConfig knowledge = new KnowledgeConfig();
    private DbTargetsConfig dbTargets = new DbTargetsConfig();
    private ApmConfig apm = new ApmConfig();
    private DigestConfig digest = new DigestConfig();
    private KafkaIngestConfig kafka = new KafkaIngestConfig();
    private ActiveMqConfig activemq = new ActiveMqConfig();

    @Data
    public static class ElasticsearchConfig {
        private String url = "http://localhost:9200";
        private String indexPattern = "logs-*";
        private int pollIntervalSeconds = 30;
        private String username = "";
        private String password = "";
    }

    @Data
    public static class JaegerConfig {
        private String url = "http://localhost:16686";
        private long slowTraceThresholdMs = 2000;
        private int pollIntervalSeconds = 60;
    }

    @Data
    public static class GrafanaConfig {
        private String url = "http://localhost:3000";
        private String apiKey = "";
        private int pollIntervalSeconds = 15;
        private List<String> datasourceUids = new ArrayList<>();
    }

    @Data
    public static class HealthProbeConfig {
        private int intervalSeconds = 10;
        private int timeoutSeconds = 5;
        private List<ProbeTarget> targets = new ArrayList<>();

        @Data
        public static class ProbeTarget {
            private String name;
            private String url;
            private String type = "HTTP"; // HTTP or GRPC
        }
    }

    @Data
    public static class CorrelationConfig {
        private int windowMinutes = 5;
        private int minSignalsForCorrelation = 2;
    }

    @Data
    public static class AlertingConfig {
        private SlackConfig slack = new SlackConfig();
        private PagerDutyConfig pagerduty = new PagerDutyConfig();
        private OpsGenieConfig opsgenie = new OpsGenieConfig();
        private TeamsConfig teams = new TeamsConfig();

        @Data
        public static class SlackConfig {
            private String webhookUrl = "";
            private String channel = "#sentinel-alerts";
        }

        @Data
        public static class PagerDutyConfig {
            private String integrationKey = "";
            private String eventsUrl = "https://events.pagerduty.com/v2/enqueue";
        }

        @Data
        public static class OpsGenieConfig {
            private String apiKey = "";
            private String alertsUrl = "https://api.opsgenie.com/v2/alerts";
        }

        @Data
        public static class TeamsConfig {
            private String defaultWebhookUrl = "";
            private Map<String, String> channelMap = new HashMap<>();
            private String kibanaBaseUrl = "http://localhost:5601";
            private String grafanaBaseUrl = "http://localhost:3000";
            private String jaegerBaseUrl = "http://localhost:16686";
        }
    }

    @Data
    public static class RemediationConfig {
        private boolean dryRun = true;
        private double maxScaleFactor = 2.0;
        private int restartCooldownMinutes = 20;
        private int maxRestartsPerHour = 3;
        private int circuitBreakerRetryMinutes = 5;
        private int maxCircuitBreakerCycles = 3;
    }

    @Data
    public static class AnomalyConfig {
        private double zScoreThreshold = 3.0;
        private int retrainingIntervalHours = 24;
        private int minSamplesForDetection = 30;
    }

    @Data
    public static class AgentConfig {
        private boolean enabled = false;
        private String mode = "advisory";
        private int maxSteps = 8;
        private int historyMaxMessages = 20;
        private int proactiveScanIntervalSeconds = 0;
        private boolean rcaOnCorrelation = false;
        private RedactionConfig redaction = new RedactionConfig();

        @Data
        public static class RedactionConfig {
            private boolean enabled = true;
            private List<String> patterns = new ArrayList<>(List.of(
                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                    "\\b\\d{12,19}\\b",
                    "\\b\\d{3}-\\d{2}-\\d{4}\\b",
                    "(?i)\\b(bearer|token|api[_-]?key|password|secret)\\s*[:=]\\s*\\S+"
            ));
            private List<String> blockedFields = new ArrayList<>(List.of(
                    "password", "secret", "token", "apiKey", "api_key", "authorization"
            ));
        }
    }

    @Data
    public static class AihubConfig {
        private String provider = "anthropic";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int maxTokens = 2000;
        private long timeoutMs = 30_000;
        private String anthropicVersion = "2023-06-01";
    }

    @Data
    public static class KnowledgeConfig {
        private String embeddingModel = "";
        private String embeddingBaseUrl = "";
        private String embeddingApiKey = "";
        private int embeddingDimension = 1024;
        private int topK = 5;
        private boolean pgvectorEnabled = false;
    }

    @Data
    public static class DbTargetsConfig {
        private List<Target> targets = new ArrayList<>();

        @Data
        public static class Target {
            private String name;
            private String url;
            private String username = "";
            private String password = "";
            private String driverClassName = "org.postgresql.Driver";
            private int queryRowLimit = 200;
            private int queryTimeoutSeconds = 5;
        }
    }

    @Data
    public static class ApmConfig {
        private String url = "";
        private String apiKey = "";
        private List<String> actuatorTargets = new ArrayList<>();
    }

    /**
     * Daily / weekly AI health digest.
     * Daily report is opt-in via {@code sentinel.digest.daily.enabled=true}.
     */
    @Data
    public static class DigestConfig {
        private DailyConfig daily = new DailyConfig();

        @Data
        public static class DailyConfig {
            private boolean enabled = false;
            /** Cron expression; default 09:00 UTC every day. */
            private String cron = "0 0 9 * * *";
            /** How far back to look when summarizing. */
            private int lookbackHours = 24;
            /** Use the AI Copilot to summarize if true and an LlmClient bean is present. */
            private boolean useAi = true;
            /** Channels to send to. Empty = log only. */
            private List<String> emailRecipients = new ArrayList<>();
            private boolean slack = true;
            /** Max number of incidents fed to the LLM (token guard). */
            private int maxIncidentsInPrompt = 50;
        }
    }

    /**
     * First-class Kafka monitoring: polls AdminClient for per-topic consumer-group
     * lag, partition state, broker availability. Emits NormalizedEvents the
     * existing MessageQueueBacklogRule and ServiceDownRule already consume.
     */
    @Data
    public static class KafkaIngestConfig {
        private boolean enabled = false;
        private String bootstrapServers = "localhost:9092";
        private int pollIntervalSeconds = 30;
        /** Map of "service-name" -> "consumer-group-id" to monitor. */
        private Map<String, String> consumerGroups = new HashMap<>();
        /** Optional explicit topic list. If empty, every topic the broker exposes is checked. */
        private List<String> topics = new ArrayList<>();
        private int adminTimeoutMs = 5_000;
    }

    /**
     * First-class ActiveMQ monitoring via Jolokia REST. Emits queue-depth metrics
     * that flow into MessageQueueBacklogRule.
     */
    @Data
    public static class ActiveMqConfig {
        private boolean enabled = false;
        /** Jolokia endpoint, e.g. http://activemq:8161/api/jolokia */
        private String jolokiaUrl = "http://localhost:8161/api/jolokia";
        private String username = "admin";
        private String password = "admin";
        private String brokerName = "localhost";
        private int pollIntervalSeconds = 30;
        /** Map of "service-name" -> "queue-name" to monitor. */
        private Map<String, String> queues = new HashMap<>();
    }
}
