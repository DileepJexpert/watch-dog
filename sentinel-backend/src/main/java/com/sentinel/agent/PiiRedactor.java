package com.sentinel.agent;

import com.sentinel.config.SentinelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NFR redaction pass. Applied to every tool output before it is sent to the LLM.
 * v1 is regex + field allow-list — flagged in the requirements as a placeholder
 * for the bank's stronger redactor.
 */
@Slf4j
@Component
public class PiiRedactor {

    private final SentinelProperties properties;
    private volatile List<Pattern> compiled = List.of();
    private volatile Set<String> blockedFields = Set.of();

    public PiiRedactor(SentinelProperties properties) {
        this.properties = properties;
        recompile();
    }

    private void recompile() {
        List<Pattern> patterns = new ArrayList<>();
        for (String p : properties.getAgent().getRedaction().getPatterns()) {
            try {
                patterns.add(Pattern.compile(p));
            } catch (Exception e) {
                log.warn("Invalid redaction pattern '{}': {}", p, e.getMessage());
            }
        }
        this.compiled = patterns;
        Set<String> fields = new HashSet<>();
        for (String f : properties.getAgent().getRedaction().getBlockedFields()) {
            fields.add(f.toLowerCase());
        }
        this.blockedFields = fields;
    }

    public Object redact(Object value) {
        if (!properties.getAgent().getRedaction().isEnabled()) return value;
        return redactDeep(value);
    }

    public String redactString(String input) {
        if (input == null) return null;
        if (!properties.getAgent().getRedaction().isEnabled()) return input;
        String out = input;
        for (Pattern p : compiled) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object redactDeep(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return redactString(s);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> redacted = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (blockedFields.contains(key.toLowerCase())) {
                    redacted.put(key, "[REDACTED]");
                } else {
                    redacted.put(key, redactDeep(e.getValue()));
                }
            }
            return redacted;
        }
        if (value instanceof List<?> l) {
            List<Object> redacted = new ArrayList<>(l.size());
            for (Object item : l) redacted.add(redactDeep(item));
            return redacted;
        }
        return value;
    }
}
