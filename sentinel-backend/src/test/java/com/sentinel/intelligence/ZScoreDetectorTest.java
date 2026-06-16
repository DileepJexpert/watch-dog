package com.sentinel.intelligence;

import com.sentinel.config.SentinelProperties;
import com.sentinel.intelligence.anomaly.ZScoreDetector;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZScoreDetectorTest {

    private ZScoreDetector detector;

    @BeforeEach
    void setup() {
        SentinelProperties props = new SentinelProperties();
        props.getAnomaly().setZScoreThreshold(3.0);
        props.getAnomaly().setMinSamplesForDetection(10);
        detector = new ZScoreDetector(props);
    }

    @Test
    void detectAndEnrich_noAnomalyOnInsuficientSamples() {
        NormalizedEvent event = NormalizedEvent.ofMetric("svc", Severity.P3_MEDIUM, "CPU",
                Map.of("metric_name", "cpu_percent", "metric_value", 99.0));

        NormalizedEvent result = detector.detectAndEnrich(event);

        // Not enough samples yet - should not annotate anomaly
        assertThat(result.attributes().get("anomaly_detected")).isNull();
    }

    @Test
    void detectAndEnrich_annotatesAnomalyAfterBaseline() {
        String service = "order-service";
        String metric = "error_rate_percent";

        // Train baseline with normal values (all ~2%)
        for (int i = 0; i < 15; i++) {
            NormalizedEvent normal = NormalizedEvent.ofMetric(service, Severity.P4_INFO, "Normal",
                    Map.of("metric_name", metric, "metric_value", 2.0 + Math.random() * 0.5));
            detector.detectAndEnrich(normal);
        }

        // Now inject an anomalous spike (50% error rate)
        NormalizedEvent anomalous = NormalizedEvent.ofMetric(service, Severity.P2_HIGH, "Spike",
                Map.of("metric_name", metric, "metric_value", 50.0));
        NormalizedEvent enriched = detector.detectAndEnrich(anomalous);

        assertThat(enriched.attributes().get("anomaly_detected")).isEqualTo(true);
        assertThat(enriched.attributes().get("z_score")).isNotNull();
        double zScore = ((Number) enriched.attributes().get("z_score")).doubleValue();
        assertThat(zScore).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void detectAndEnrich_returnsOriginalEventWithoutMetrics() {
        NormalizedEvent event = NormalizedEvent.ofLog("svc", Severity.P2_HIGH,
                "Error message", Map.of("log_level", "ERROR"));

        NormalizedEvent result = detector.detectAndEnrich(event);

        // Log events without metric_name/metric_value should pass through unchanged
        assertThat(result).isEqualTo(event);
    }
}
