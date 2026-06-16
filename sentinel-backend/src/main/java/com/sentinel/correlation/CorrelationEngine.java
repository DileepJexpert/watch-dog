package com.sentinel.correlation;

import com.sentinel.agent.RcaService;
import com.sentinel.alerting.AlertingService;
import com.sentinel.config.KafkaConfig;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.entity.ServiceHealthEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.ServiceStatus;
import com.sentinel.correlation.rules.CorrelationRule;
import com.sentinel.normalization.EventNormalizationService;
import com.sentinel.remediation.AutoRemediationEngine;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.ServiceHealthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core correlation engine. Consumes normalized events from Kafka,
 * adds them to the sliding window, and evaluates all correlation rules
 * every 30 seconds to detect incidents.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorrelationEngine {

    private final SlidingWindowBuffer windowBuffer;
    private final List<CorrelationRule> correlationRules;
    private final IncidentRepository incidentRepository;
    private final ServiceHealthRepository serviceHealthRepository;
    private final AlertingService alertingService;
    private final AutoRemediationEngine autoRemediationEngine;
    private final EventNormalizationService normalizationService;
    private final SimpMessagingTemplate messagingTemplate;

    /** Optional — only present when the AI layer is enabled (FR-7). */
    @Autowired(required = false)
    private RcaService rcaService;

    /**
     * Consumes normalized events from Kafka and adds to sliding window.
     */
    @KafkaListener(topics = KafkaConfig.EVENTS_TOPIC, groupId = "sentinel-correlation")
    public void onEvent(NormalizedEvent event) {
        NormalizedEvent normalized = normalizationService.normalize(event);
        if (normalized != null) {
            windowBuffer.add(normalized);
            updateServiceHealth(normalized);
        }
    }

    /**
     * Evaluates all correlation rules across all active services every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void evaluateRules() {
        for (String serviceName : windowBuffer.getActiveServices()) {
            List<NormalizedEvent> windowEvents = windowBuffer.getWindow(serviceName);
            if (windowEvents.isEmpty()) continue;

            for (CorrelationRule rule : correlationRules) {
                try {
                    Optional<IncidentEntity> incident = rule.evaluate(windowEvents, serviceName);
                    incident.ifPresent(i -> handleNewIncident(i, serviceName));
                } catch (Exception e) {
                    log.warn("Rule {} failed for service {}: {}", rule.getName(), serviceName, e.getMessage());
                }
            }

            // Cleanup expired events
            windowBuffer.cleanup(serviceName);
        }
    }

    private void handleNewIncident(IncidentEntity incident, String serviceName) {
        // De-duplicate: skip if same rule already has an OPEN incident for this service
        boolean alreadyOpen = incidentRepository
                .existsByServiceNameAndCorrelationRuleAndStatusIn(
                        serviceName,
                        incident.getCorrelationRule(),
                        List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING));

        if (alreadyOpen) return;

        IncidentEntity saved = incidentRepository.save(incident);
        log.warn("NEW INCIDENT [{}] {} - {} for service {}",
                saved.getSeverity(), saved.getId(), saved.getTitle(), serviceName);

        // Update service health to RED
        updateServiceHealthStatus(serviceName, ServiceStatus.RED, saved.getId().toString());

        // Send real-time WebSocket notification
        messagingTemplate.convertAndSend("/topic/incidents", saved);

        // Trigger alerting
        alertingService.alert(saved);

        // Trigger auto-remediation
        autoRemediationEngine.remediate(saved);

        // FR-7: optional LLM-assisted RCA pass. Async + best-effort; never blocks
        // or affects the deterministic alerting/remediation path above.
        if (rcaService != null) {
            try {
                rcaService.analyze(saved);
            } catch (Exception e) {
                log.debug("RCA dispatch failed (non-fatal): {}", e.getMessage());
            }
        }
    }

    private void updateServiceHealth(NormalizedEvent event) {
        serviceHealthRepository.findById(event.serviceName()).ifPresentOrElse(
                entity -> {
                    // Update last seen, keep existing status unless improving
                    entity.setLastUpdated(Instant.now());
                    serviceHealthRepository.save(entity);
                },
                () -> {
                    ServiceHealthEntity health = new ServiceHealthEntity();
                    health.setServiceName(event.serviceName());
                    health.setStatus(ServiceStatus.GREEN);
                    serviceHealthRepository.save(health);
                }
        );
    }

    private void updateServiceHealthStatus(String serviceName, ServiceStatus status, String incidentId) {
        serviceHealthRepository.findById(serviceName).ifPresentOrElse(
                entity -> {
                    entity.setStatus(status);
                    entity.setActiveIncidentId(incidentId);
                    serviceHealthRepository.save(entity);
                },
                () -> {
                    ServiceHealthEntity health = new ServiceHealthEntity();
                    health.setServiceName(serviceName);
                    health.setStatus(status);
                    health.setActiveIncidentId(incidentId);
                    serviceHealthRepository.save(health);
                }
        );
    }
}
