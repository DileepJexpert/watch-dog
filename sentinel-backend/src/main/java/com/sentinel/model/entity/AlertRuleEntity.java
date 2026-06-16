package com.sentinel.model.entity;

import com.sentinel.model.enums.Severity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
@Data
@NoArgsConstructor
public class AlertRuleEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "condition_yaml", columnDefinition = "TEXT")
    private String conditionYaml;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private boolean enabled = true;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
