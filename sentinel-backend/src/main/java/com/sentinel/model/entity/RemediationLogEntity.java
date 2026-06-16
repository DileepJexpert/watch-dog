package com.sentinel.model.entity;

import com.sentinel.model.enums.RemediationActionType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "remediation_log")
@Data
@NoArgsConstructor
public class RemediationLogEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "incident_id", columnDefinition = "uuid")
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private RemediationActionType actionType;

    @Column(name = "service_name")
    private String serviceName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    /** SUCCESS, FAILED, SKIPPED, DRY_RUN */
    private String outcome;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    /** 'AUTO' for automated actions or engineer username */
    @Column(name = "executed_by")
    private String executedBy;

    @Column(name = "failure_reason")
    private String failureReason;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (executedAt == null) executedAt = Instant.now();
    }
}
