package com.sentinel.scheduler;

import com.sentinel.intelligence.anomaly.AnomalyDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Persists in-memory anomaly detection baselines to the database every 24 hours.
 * This allows the models to survive application restarts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyTrainingScheduler {

    private final AnomalyDetectionService anomalyDetectionService;

    @Scheduled(fixedRateString = "${sentinel.anomaly.retraining-interval-hours:24}",
               timeUnit = java.util.concurrent.TimeUnit.HOURS)
    public void persistBaselines() {
        log.info("Starting anomaly baseline persistence...");
        try {
            anomalyDetectionService.persistBaselines();
            log.info("Anomaly baseline persistence complete");
        } catch (Exception e) {
            log.error("Failed to persist anomaly baselines: {}", e.getMessage(), e);
        }
    }
}
