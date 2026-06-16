package com.sentinel.repository;

import com.sentinel.model.entity.RemediationLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RemediationRepository extends JpaRepository<RemediationLogEntity, UUID> {

    List<RemediationLogEntity> findByIncidentIdOrderByExecutedAtDesc(UUID incidentId);

    Page<RemediationLogEntity> findAllByOrderByExecutedAtDesc(Pageable pageable);

    List<RemediationLogEntity> findByServiceNameAndExecutedAtAfterOrderByExecutedAtDesc(
            String serviceName, Instant after);

    long countByServiceNameAndActionTypeAndExecutedAtAfter(
            String serviceName, com.sentinel.model.enums.RemediationActionType actionType, Instant after);
}
