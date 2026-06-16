package com.sentinel.intelligence;

import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Evaluates YAML-based static rules against a window of events.
 * Complements the correlation rules with configurable threshold conditions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngine {

    private final RuleLoader ruleLoader;

    /**
     * Checks if any enabled static rule fires for the given service and events.
     *
     * @return list of rule names that fired
     */
    public List<String> evaluate(List<NormalizedEvent> windowEvents, String serviceName) {
        return ruleLoader.getRules().stream()
                .filter(rule -> appliesToService(rule, serviceName))
                .filter(rule -> evaluateRule(rule, windowEvents))
                .map(RuleDefinition::getName)
                .toList();
    }

    private boolean appliesToService(RuleDefinition rule, String serviceName) {
        return rule.getServiceName() == null || rule.getServiceName().equals(serviceName);
    }

    private boolean evaluateRule(RuleDefinition rule, List<NormalizedEvent> events) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) return false;

        if ("OR".equalsIgnoreCase(rule.getOperator())) {
            return rule.getConditions().stream().anyMatch(c -> evaluateCondition(c, events));
        } else { // AND
            return rule.getConditions().stream().allMatch(c -> evaluateCondition(c, events));
        }
    }

    private boolean evaluateCondition(RuleDefinition.Condition condition, List<NormalizedEvent> events) {
        List<NormalizedEvent> filtered = events;

        // Filter by signal type if specified
        if (condition.getSignalType() != null) {
            try {
                SignalType type = SignalType.valueOf(condition.getSignalType().toUpperCase());
                filtered = events.stream().filter(e -> e.signalType() == type).toList();
            } catch (IllegalArgumentException ignored) {}
        }

        if (filtered.isEmpty()) return false;

        return switch (condition.getComparator().toUpperCase()) {
            case "GT" -> filtered.stream().anyMatch(e -> getNumericValue(e, condition) > condition.getThreshold());
            case "GTE" -> filtered.stream().anyMatch(e -> getNumericValue(e, condition) >= condition.getThreshold());
            case "LT" -> filtered.stream().anyMatch(e -> getNumericValue(e, condition) < condition.getThreshold());
            case "LTE" -> filtered.stream().anyMatch(e -> getNumericValue(e, condition) <= condition.getThreshold());
            case "EQ" -> filtered.stream().anyMatch(e -> getNumericValue(e, condition) == condition.getThreshold());
            case "CONTAINS" -> condition.getPattern() != null &&
                    filtered.stream().anyMatch(e -> e.message() != null &&
                            e.message().toLowerCase().contains(condition.getPattern().toLowerCase()));
            default -> false;
        };
    }

    private double getNumericValue(NormalizedEvent event, RuleDefinition.Condition condition) {
        if (condition.getMetricName() == null) return 0.0;
        Object val = event.attributes().get(condition.getMetricName());
        if (val instanceof Number) return ((Number) val).doubleValue();
        // Try metric_value if metric_name matches
        Object metricName = event.attributes().get("metric_name");
        if (condition.getMetricName().equals(metricName)) {
            Object metricValue = event.attributes().get("metric_value");
            if (metricValue instanceof Number) return ((Number) metricValue).doubleValue();
        }
        return 0.0;
    }
}
