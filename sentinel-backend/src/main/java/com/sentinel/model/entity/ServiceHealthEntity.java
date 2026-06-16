package com.sentinel.model.entity;

import com.sentinel.model.enums.ServiceStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "service_health")
@Data
@NoArgsConstructor
public class ServiceHealthEntity {

    @Id
    @Column(name = "service_name")
    private String serviceName;

    @Enumerated(EnumType.STRING)
    private ServiceStatus status;

    @Column(name = "error_rate")
    private Double errorRate;

    @Column(name = "latency_p95")
    private Double latencyP95;

    @Column(name = "latency_p99")
    private Double latencyP99;

    @Column(name = "request_rate")
    private Double requestRate;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @Column(name = "active_incident_id")
    private String activeIncidentId;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = Instant.now();
        if (status == null) status = ServiceStatus.GREEN;
    }
}
