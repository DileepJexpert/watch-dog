package com.sentinel.correlation.rules;

import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.entity.IncidentEntity;

import java.util.List;
import java.util.Optional;

/**
 * Interface for all correlation rules in SENTINEL.
 * Each rule evaluates a sliding window of events for a specific service
 * and optionally produces a correlated incident.
 */
public interface CorrelationRule {

    /**
     * Unique name identifying this rule (used in incident correlation_rule field).
     */
    String getName();

    /**
     * Evaluates the events in the sliding window for the given service.
     *
     * @param windowEvents all normalized events for the service in the current 5-minute window
     * @param serviceName  the service being evaluated
     * @return an incident if this rule's conditions are met, or empty
     */
    Optional<IncidentEntity> evaluate(List<NormalizedEvent> windowEvents, String serviceName);
}
