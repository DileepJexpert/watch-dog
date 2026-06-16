package com.sentinel.remediation.actions;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.RemediationActionType;
import com.sentinel.remediation.KubernetesClientWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Horizontally scales a Kubernetes deployment in response to resource exhaustion incidents.
 * Guardrail: max 2x current replicas, 10-minute cooldown enforced by GuardrailService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodScaleAction implements RemediationActionExecutor {

    private static final String DEFAULT_NAMESPACE = "production";
    private static final Set<String> TRIGGERING_RULES = Set.of(
            "MEMORY_LEAK_RESOURCE_EXHAUSTION",
            "HIGH_CPU_SUSTAINED",
            "HIGH_ERROR_RATE"
    );

    private final KubernetesClientWrapper k8sClient;
    private final SentinelProperties properties;

    @Override
    public RemediationActionType getActionType() {
        return RemediationActionType.POD_SCALE_HORIZONTAL;
    }

    @Override
    public boolean appliesTo(IncidentEntity incident) {
        return TRIGGERING_RULES.contains(incident.getCorrelationRule());
    }

    @Override
    public boolean execute(IncidentEntity incident, boolean dryRun) {
        String serviceName = incident.getServiceName();
        int currentReplicas = k8sClient.getCurrentReplicas(DEFAULT_NAMESPACE, serviceName);

        if (currentReplicas < 0) {
            log.warn("Could not determine current replica count for {}, skipping scale", serviceName);
            return false;
        }

        double maxScaleFactor = properties.getRemediation().getMaxScaleFactor();
        int targetReplicas = Math.min(
                (int) Math.ceil(currentReplicas * maxScaleFactor),
                currentReplicas * 2
        );

        log.info("AUTO-REMEDIATION [{}]: Scaling {} from {} to {} replicas (dryRun={})",
                incident.getId(), serviceName, currentReplicas, targetReplicas, dryRun);

        if (dryRun) return true;

        return k8sClient.scaleDeployment(DEFAULT_NAMESPACE, serviceName, targetReplicas);
    }
}
