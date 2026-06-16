package com.sentinel.model;

import com.sentinel.model.enums.Severity;
import com.sentinel.model.enums.SignalType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Common normalized event schema. All ingestion sources (Elasticsearch, Jaeger,
 * Grafana, HealthProbe) map their raw data to this structure before entering
 * the correlation engine.
 */
public record NormalizedEvent(
        String id,
        Instant timestamp,
        String serviceName,
        SignalType signalType,
        Severity severity,
        String message,
        Map<String, Object> attributes
) {
    public NormalizedEvent {
        if (id == null) id = UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now();
        if (attributes == null) attributes = Map.of();
    }

    /** Convenience factory for log events from Elasticsearch */
    public static NormalizedEvent ofLog(String serviceName, Severity severity, String message,
                                        Map<String, Object> attributes) {
        return new NormalizedEvent(null, Instant.now(), serviceName, SignalType.LOG,
                severity, message, attributes);
    }

    /** Convenience factory for trace events from Jaeger */
    public static NormalizedEvent ofTrace(String serviceName, Severity severity, String message,
                                          Map<String, Object> attributes) {
        return new NormalizedEvent(null, Instant.now(), serviceName, SignalType.TRACE,
                severity, message, attributes);
    }

    /** Convenience factory for metric events from Grafana */
    public static NormalizedEvent ofMetric(String serviceName, Severity severity, String message,
                                           Map<String, Object> attributes) {
        return new NormalizedEvent(null, Instant.now(), serviceName, SignalType.METRIC,
                severity, message, attributes);
    }

    /** Convenience factory for health probe events */
    public static NormalizedEvent ofProbe(String serviceName, Severity severity, String message,
                                          Map<String, Object> attributes) {
        return new NormalizedEvent(null, Instant.now(), serviceName, SignalType.PROBE,
                severity, message, attributes);
    }
}
