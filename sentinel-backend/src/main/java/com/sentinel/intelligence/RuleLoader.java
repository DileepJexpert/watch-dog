package com.sentinel.intelligence;

import com.sentinel.repository.AlertRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads static alert rules from YAML file and merges with database rules.
 * Rules are hot-reloadable via refreshRules().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleLoader {

    private final AlertRuleRepository alertRuleRepository;
    private final CopyOnWriteArrayList<RuleDefinition> loadedRules = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        refreshRules();
    }

    public List<RuleDefinition> getRules() {
        return Collections.unmodifiableList(loadedRules);
    }

    /**
     * Reloads rules from YAML file and database.
     */
    public void refreshRules() {
        List<RuleDefinition> rules = new ArrayList<>();
        rules.addAll(loadFromYaml());
        rules.addAll(loadFromDatabase());
        loadedRules.clear();
        loadedRules.addAll(rules);
        log.info("Loaded {} alert rules ({} from YAML, {} from DB)",
                rules.size(), loadFromYaml().size(), loadFromDatabase().size());
    }

    @SuppressWarnings("unchecked")
    private List<RuleDefinition> loadFromYaml() {
        List<RuleDefinition> rules = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource("rules/default-rules.yml");
            if (!resource.exists()) return rules;

            Yaml yaml = new Yaml();
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> doc = yaml.load(is);
                List<Map<String, Object>> rawRules = (List<Map<String, Object>>) doc.get("rules");
                if (rawRules == null) return rules;

                for (Map<String, Object> raw : rawRules) {
                    RuleDefinition rule = mapToRuleDefinition(raw);
                    if (rule.isEnabled()) {
                        rules.add(rule);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load rules from YAML: {}", e.getMessage());
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private RuleDefinition mapToRuleDefinition(Map<String, Object> raw) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId((String) raw.get("id"));
        rule.setName((String) raw.get("name"));
        rule.setServiceName((String) raw.get("serviceName"));
        rule.setDescription((String) raw.get("description"));
        rule.setEnabled(raw.getOrDefault("enabled", true).equals(true));
        rule.setOperator((String) raw.getOrDefault("operator", "AND"));

        String severityStr = (String) raw.get("severity");
        if (severityStr != null) {
            try {
                rule.setSeverity(com.sentinel.model.enums.Severity.valueOf(severityStr));
            } catch (Exception ignored) {}
        }

        List<Map<String, Object>> rawConditions = (List<Map<String, Object>>) raw.get("conditions");
        if (rawConditions != null) {
            List<RuleDefinition.Condition> conditions = rawConditions.stream()
                    .map(this::mapToCondition)
                    .toList();
            rule.setConditions(conditions);
        }
        return rule;
    }

    private RuleDefinition.Condition mapToCondition(Map<String, Object> raw) {
        RuleDefinition.Condition c = new RuleDefinition.Condition();
        c.setMetricName((String) raw.get("metricName"));
        c.setSignalType((String) raw.get("signalType"));
        c.setComparator((String) raw.getOrDefault("comparator", "GT"));
        c.setPattern((String) raw.get("pattern"));

        Object threshold = raw.get("threshold");
        if (threshold instanceof Number) {
            c.setThreshold(((Number) threshold).doubleValue());
        }

        Object duration = raw.get("durationMinutes");
        if (duration instanceof Number) {
            c.setDurationMinutes(((Number) duration).intValue());
        }
        return c;
    }

    private List<RuleDefinition> loadFromDatabase() {
        List<RuleDefinition> rules = new ArrayList<>();
        try {
            alertRuleRepository.findByEnabledTrue().forEach(entity -> {
                RuleDefinition rule = new RuleDefinition();
                rule.setId(entity.getId().toString());
                rule.setName(entity.getName());
                rule.setServiceName(entity.getServiceName());
                rule.setSeverity(entity.getSeverity());
                rule.setEnabled(entity.isEnabled());
                // Parse condition_yaml field
                rules.add(rule);
            });
        } catch (Exception e) {
            log.warn("Failed to load rules from database: {}", e.getMessage());
        }
        return rules;
    }
}
