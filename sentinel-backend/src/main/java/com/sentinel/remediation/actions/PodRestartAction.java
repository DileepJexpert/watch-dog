package com.sentinel.remediation.actions;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.RemediationActionType;
import com.sentinel.remediation.KubernetesClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Restarts pods for a service by deleting them (Kubernetes recreates automatically).
 * Guardrail: max 3 restarts per hour per service, enforced by GuardrailService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodRestartAction implements RemediationActionExecutor {

    private static final String DEFAULT_NAMESPACE = "production";
    private static final Set<String> TRIGGERING_RULES = Set.of(
            "POD_OOM_KILL",
            "CONNECTION_POOL_EXHAUSTION",
            "SERVICE_DOWN"
    );

    private final KubernetesClientWrapper k8sClient;

    @Override
    public RemediationActionType getActionType() {
        return RemediationActionType.POD_RESTART;
    }

    @Override
    public boolean appliesTo(IncidentEntity incident) {
        return TRIGGERING_RULES.contains(incident.getCorrelationRule());
    }

    @Override
    public boolean execute(IncidentEntity incident, boolean dryRun) {
        String serviceName = incident.getServiceName();
        log.info("AUTO-REMEDIATION [{}]: Restarting pods for {} (dryRun={})",
                incident.getId(), serviceName, dryRun);

        if (dryRun) return true;

        return k8sClient.restartPods(DEFAULT_NAMESPACE, serviceName);
    }
}
