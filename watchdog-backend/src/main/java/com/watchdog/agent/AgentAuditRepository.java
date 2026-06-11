package com.watchdog.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AgentAuditRepository extends JpaRepository<AgentAuditEntity, Long> {
    Page<AgentAuditEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
