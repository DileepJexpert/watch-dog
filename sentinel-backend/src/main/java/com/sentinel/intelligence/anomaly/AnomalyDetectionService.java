package com.sentinel.intelligence.anomaly;

import com.sentinel.model.entity.BaselineMetricEntity;
import com.sentinel.repository.BaselineMetricRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates anomaly detection lifecycle:
 * - Loads baselines from DB on startup
 * - Delegates detection to ZScoreDetector
 * - Persists updated baselines on retraining schedule
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private final ZScoreDetector zScoreDetector;
    private final BaselineMetricRepository baselineMetricRepository;

    @PostConstruct
    public void loadBaselinesFromDatabase() {
        List<BaselineMetricEntity> entities = baselineMetricRepository.findAll();
        zScoreDetector.loadBaselines(entities);
        log.info("Anomaly detection service initialized with {} baseline records", entities.size());
    }

    /**
     * Persists all in-memory baselines to the database.
     * Called by AnomalyTrainingScheduler every 24 hours.
     */
    public void persistBaselines() {
        zScoreDetector.getAllBaselines().forEach(model -> {
            BaselineMetricEntity entity = baselineMetricRepository
                    .findByServiceNameAndMetricNameAndHourOfDayAndDayOfWeek(
                            model.getServiceName(), model.getMetricName(),
                            model.getHourOfDay(), model.getDayOfWeek())
                    .orElse(new BaselineMetricEntity());

            entity.setServiceName(model.getServiceName());
            entity.setMetricName(model.getMetricName());
            entity.setHourOfDay(model.getHourOfDay());
            entity.setDayOfWeek(model.getDayOfWeek());
            entity.setMean(model.getMean());
            entity.setStdDev(model.getStdDev());
            entity.setSampleCount(model.getCount());

            baselineMetricRepository.save(entity);
        });

        log.info("Persisted {} baseline models to database", zScoreDetector.getAllBaselines().size());
    }
}
