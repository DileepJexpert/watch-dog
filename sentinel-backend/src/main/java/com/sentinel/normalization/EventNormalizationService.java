package com.sentinel.normalization;

import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates and enriches normalized events before they enter the correlation engine.
 * Ensures all required fields are present and applies cross-source enrichment.
 */
@Slf4j
@Service
public class EventNormalizationService {

    /**
     * Validates and enriches a normalized event.
     * Returns the enriched event or null if the event should be dropped.
     */
    public NormalizedEvent normalize(NormalizedEvent event) {
        if (event == null) return null;

        // Drop P4_INFO events from non-probe sources to reduce noise
        if (Severity.P4_INFO.equals(event.severity()) &&
                event.signalType() != null &&
                event.signalType().name().equals("PROBE")) {
            return null; // Don't process routine successful probes
        }

        // Ensure service name is clean
        String serviceName = sanitizeServiceName(event.serviceName());

        // Enrich attributes
        Map<String, Object> enriched = new HashMap<>(event.attributes());
        enriched.put("ingested_at", Instant.now().toString());
        enriched.put("signal_source", event.signalType() != null ? event.signalType().name() : "UNKNOWN");

        return new NormalizedEvent(
                event.id(),
                event.timestamp(),
                serviceName,
                event.signalType(),
                event.severity(),
                event.message(),
                enriched
        );
    }

    private String sanitizeServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return "unknown";
        // Strip namespace prefix if present (e.g., "prod/payment-service" -> "payment-service")
        if (serviceName.contains("/")) {
            serviceName = serviceName.substring(serviceName.lastIndexOf('/') + 1);
        }
        return serviceName.toLowerCase().trim();
    }
}
