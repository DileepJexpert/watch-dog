package com.sentinel.model.enums;

/**
 * Source signal type for a normalized event.
 */
public enum SignalType {
    LOG,    // From Elasticsearch/Kibana
    TRACE,  // From Jaeger
    METRIC, // From Grafana/Prometheus
    PROBE   // From active health probes
}
