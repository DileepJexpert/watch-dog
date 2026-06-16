package com.sentinel.api;

import com.sentinel.intelligence.RuleLoader;
import com.sentinel.model.entity.AlertRuleEntity;
import com.sentinel.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Self-service rule builder API. Engineers can create, update, and delete alert rules.
 */
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RuleController {

    private final AlertRuleRepository alertRuleRepository;
    private final RuleLoader ruleLoader;

    @GetMapping
    public ResponseEntity<List<AlertRuleEntity>> listRules() {
        return ResponseEntity.ok(alertRuleRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertRuleEntity> getRule(@PathVariable UUID id) {
        return alertRuleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AlertRuleEntity> createRule(@RequestBody AlertRuleEntity rule) {
        AlertRuleEntity saved = alertRuleRepository.save(rule);
        ruleLoader.refreshRules(); // Hot-reload
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AlertRuleEntity> updateRule(@PathVariable UUID id,
                                                       @RequestBody AlertRuleEntity updated) {
        return alertRuleRepository.findById(id).map(existing -> {
            existing.setName(updated.getName());
            existing.setConditionYaml(updated.getConditionYaml());
            existing.setSeverity(updated.getSeverity());
            existing.setEnabled(updated.isEnabled());
            existing.setServiceName(updated.getServiceName());
            AlertRuleEntity saved = alertRuleRepository.save(existing);
            ruleLoader.refreshRules();
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        if (!alertRuleRepository.existsById(id)) return ResponseEntity.notFound().build();
        alertRuleRepository.deleteById(id);
        ruleLoader.refreshRules();
        return ResponseEntity.noContent().build();
    }
}
