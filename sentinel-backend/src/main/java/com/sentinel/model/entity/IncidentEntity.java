package com.sentinel.model.entity;

import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.Severity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "incidents")
@Data
@NoArgsConstructor
public class IncidentEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "service_name")
    private String serviceName;

    @Column(length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    private IncidentStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correlated_signals", columnDefinition = "jsonb")
    private List<String> correlatedSignalIds;

    @Column(name = "correlation_rule")
    private String correlationRule;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "auto_remediated")
    private boolean autoRemediated;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (detectedAt == null) detectedAt = Instant.now();
        if (status == null) status = IncidentStatus.OPEN;
    }
}
