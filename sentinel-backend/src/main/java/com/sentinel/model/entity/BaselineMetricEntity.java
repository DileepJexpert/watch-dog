package com.sentinel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "baseline_metrics",
       indexes = {
           @Index(name = "idx_baseline_service_metric", columnList = "service_name, metric_name"),
           @Index(name = "idx_baseline_time", columnList = "hour_of_day, day_of_week")
       })
@Data
@NoArgsConstructor
public class BaselineMetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "hour_of_day")
    private Integer hourOfDay;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    private Double mean;

    @Column(name = "std_dev")
    private Double stdDev;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
