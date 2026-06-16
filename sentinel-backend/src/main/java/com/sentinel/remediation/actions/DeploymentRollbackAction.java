package com.sentinel.remediation.actions;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.RemediationActionType;
import com.sentinel.remediation.KubernetesClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Rolls back a Kubernetes deployment to its previous revision.
 * Triggers on crash loops and deployment regressions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentRollbackAction implements RemediationActionExecutor {

    private static final String DEFAULT_NAMESPACE = "production";
    private static final Set<String> TRIGGERING_RULES = Set.of(
            "CRASH_LOOP_UNSTABLE_DEPLOYMENT",
            "DEPLOYMENT_REGRESSION"
    );

    private final KubernetesClientWrapper k8sClient;

    @Override
    public RemediationActionType getActionType() {
        return RemediationActionType.DEPLOYMENT_ROLLBACK;
    }

    @Override
    public boolean appliesTo(IncidentEntity incident) {
        return TRIGGERING_RULES.contains(incident.getCorrelationRule());
    }

    @Override
    public boolean execute(IncidentEntity incident, boolean dryRun) {
        String serviceName = incident.getServiceName();
        log.warn("AUTO-REMEDIATION [{}]: Rolling back deployment {} (dryRun={})",
                incident.getId(), serviceName, dryRun);

        if (dryRun) return true;

        return k8sClient.rollbackDeployment(DEFAULT_NAMESPACE, serviceName);
    }
}
