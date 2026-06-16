package com.sentinel.repository;

import com.sentinel.model.entity.AlertRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRuleEntity, UUID> {

    List<AlertRuleEntity> findByEnabledTrue();

    List<AlertRuleEntity> findByServiceNameAndEnabledTrue(String serviceName);
}
