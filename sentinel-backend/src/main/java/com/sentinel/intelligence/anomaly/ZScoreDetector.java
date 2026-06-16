package com.sentinel.intelligence.anomaly;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Z-Score based anomaly detector.
 * Maintains per-service, per-metric, per-hour-of-day, per-day-of-week baselines.
 * Emits anomaly-enriched events when z-score exceeds threshold.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZScoreDetector {

    private final SentinelProperties properties;

    // Key: "serviceName:metricName:hourOfDay:dayOfWeek"
    private final Map<String, BaselineModel> baselines = new ConcurrentHashMap<>();

    /**
     * Checks a metric value against the baseline and annotates the event if anomalous.
     * Returns an enriched copy of the event with anomaly fields, or the original if no anomaly.
     */
    public NormalizedEvent detectAndEnrich(NormalizedEvent event) {
        Object metricNameObj = event.attributes().get("metric_name");
        Object metricValueObj = event.attributes().get("metric_value");

        if (metricNameObj == null || !(metricValueObj instanceof Number)) {
            return event;
        }

        String metricName = metricNameObj.toString();
        double value = ((Number) metricValueObj).doubleValue();

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        int hour = now.getHour();
        int dow = now.getDayOfWeek().getValue();

        String key = event.serviceName() + ":" + metricName + ":" + hour + ":" + dow;
        BaselineModel model = baselines.computeIfAbsent(key,
                k -> new BaselineModel(event.serviceName(), metricName, hour, dow));

        // Update baseline before checking (online learning)
        model.update(value);

        int minSamples = properties.getAnomaly().getMinSamplesForDetection();
        double threshold = properties.getAnomaly().getZScoreThreshold();

        if (!model.hasEnoughSamples(minSamples)) {
            return event; // Not enough data yet
        }

        double zScore = model.getZScore(value);
        if (zScore >= threshold) {
            Map<String, Object> enriched = new HashMap<>(event.attributes());
            enriched.put("anomaly_detected", true);
            enriched.put("z_score", zScore);
            enriched.put("baseline_mean", model.getMean());
            enriched.put("baseline_std_dev", model.getStdDev());

            log.info("Anomaly detected: service={} metric={} value={} z-score={:.2f}",
                    event.serviceName(), metricName, value, zScore);

            return new NormalizedEvent(event.id(), event.timestamp(), event.serviceName(),
                    event.signalType(), event.severity(), event.message(), enriched);
        }

        return event;
    }

    /**
     * Loads persisted baseline data (called on startup and after retraining).
     */
    public void loadBaselines(List<com.sentinel.model.entity.BaselineMetricEntity> entities) {
        baselines.clear();
        for (var entity : entities) {
            String key = entity.getServiceName() + ":" + entity.getMetricName() + ":" +
                    entity.getHourOfDay() + ":" + entity.getDayOfWeek();
            BaselineModel model = new BaselineModel(entity.getServiceName(),
                    entity.getMetricName(), entity.getHourOfDay(), entity.getDayOfWeek());
            // Restore Welford state from persisted mean/stddev/count
            // We approximate: set count, mean, and reconstruct m2
            int count = entity.getSampleCount() != null ? entity.getSampleCount() : 0;
            double mean = entity.getMean() != null ? entity.getMean() : 0.0;
            double stdDev = entity.getStdDev() != null ? entity.getStdDev() : 0.0;

            for (int i = 0; i < count; i++) {
                model.update(mean); // Pre-warm with mean values
            }
            baselines.put(key, model);
        }
        log.info("Loaded {} baseline models from database", entities.size());
    }

    /**
     * Returns all current baselines for persistence.
     */
    public Collection<BaselineModel> getAllBaselines() {
        return baselines.values();
    }
}
