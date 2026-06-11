package com.watchdog.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeRepository extends JpaRepository<KnowledgeDocEntity, Long> {
    List<KnowledgeDocEntity> findBySourceType(String sourceType);
}
