package com.sentinel.normalization;

import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import com.sentinel.model.enums.SignalType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventNormalizationServiceTest {

    private final EventNormalizationService service = new EventNormalizationService();

    @Test
    void normalize_sanitizesServiceName() {
        NormalizedEvent event = new NormalizedEvent(null, null, "PROD/Payment-Service",
                SignalType.LOG, Severity.P2_HIGH, "Error", Map.of());

        NormalizedEvent result = service.normalize(event);

        assertThat(result).isNotNull();
        assertThat(result.serviceName()).isEqualTo("payment-service");
    }

    @Test
    void normalize_enrichesAttributes() {
        NormalizedEvent event = NormalizedEvent.ofLog("svc", Severity.P2_HIGH, "Error", Map.of());

        NormalizedEvent result = service.normalize(event);

        assertThat(result).isNotNull();
        assertThat(result.attributes()).containsKey("ingested_at");
        assertThat(result.attributes()).containsKey("signal_source");
        assertThat(result.attributes().get("signal_source")).isEqualTo("LOG");
    }

    @Test
    void normalize_dropsP4ProbeEvents() {
        NormalizedEvent event = NormalizedEvent.ofProbe("svc", Severity.P4_INFO, "OK", Map.of());

        NormalizedEvent result = service.normalize(event);

        assertThat(result).isNull();
    }

    @Test
    void normalize_handlesNullEvent() {
        assertThat(service.normalize(null)).isNull();
    }

    @Test
    void normalize_handlesBlankServiceName() {
        NormalizedEvent event = new NormalizedEvent(null, null, "",
                SignalType.METRIC, Severity.P3_MEDIUM, "Msg", Map.of());

        NormalizedEvent result = service.normalize(event);

        assertThat(result).isNotNull();
        assertThat(result.serviceName()).isEqualTo("unknown");
    }
}
