package com.watchdog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "watchdog")
public class WatchdogProperties {

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
            private String channel = "#watchdog-alerts";
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
}
