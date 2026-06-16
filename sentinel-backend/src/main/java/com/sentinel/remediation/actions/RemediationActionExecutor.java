package com.sentinel.remediation.actions;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.RemediationActionType;

/**
 * Interface for all auto-remediation action executors.
 */
public interface RemediationActionExecutor {

    /**
     * The type of remediation action this executor handles.
     */
    RemediationActionType getActionType();

    /**
     * Executes the remediation action for the given incident.
     * Returns true if successful, false if failed.
     *
     * @param incident the incident that triggered this action
     * @param dryRun   if true, logs the action but does not execute
     */
    boolean execute(IncidentEntity incident, boolean dryRun);

    /**
     * Returns true if this executor applies to the given incident/correlation rule.
     */
    boolean appliesTo(IncidentEntity incident);
}
