package com.sentinel.intelligence;

import com.sentinel.model.enums.Severity;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * YAML-deserialized static rule definition.
 * Supports threshold-based conditions for configurable alerting.
 */
@Data
public class RuleDefinition {

    private String id;
    private String name;
    private String serviceName; // null = applies to all services
    private String description;
    private Severity severity;
    private boolean enabled = true;
    private List<Condition> conditions;
    private String operator = "AND"; // AND or OR

    @Data
    public static class Condition {
        private String metricName;    // e.g. "error_rate_percent"
        private String signalType;    // LOG, TRACE, METRIC, PROBE
        private String comparator;    // GT, LT, GTE, LTE, EQ, CONTAINS
        private double threshold;
        private String pattern;       // for CONTAINS comparator (log message pattern)
        private int durationMinutes;  // must be true for this many minutes
    }
}
