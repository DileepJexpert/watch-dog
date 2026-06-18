package com.sentinel.remediation;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.model.enums.RemediationActionType;
import com.sentinel.model.enums.Severity;
import com.sentinel.repository.RemediationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardrailServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private RemediationRepository remediationRepository;

    private GuardrailService guardrailService;

    @BeforeEach
    void setup() {
        SentinelProperties props = new SentinelProperties();
        props.getRemediation().setMaxRestartsPerHour(3);
        props.getRemediation().setRestartCooldownMinutes(20);

        // Lenient — used by the restart-limit and recordAction tests, not by the
        // POD_SCALE_HORIZONTAL "no cooldown" / "cooldown active" paths which
        // short-circuit before touching opsForValue().
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        guardrailService = new GuardrailService(redisTemplate, remediationRepository, props);
    }

    @Test
    void isAllowed_returnsTrueWhenNoCooldown() {
        when(redisTemplate.hasKey(any())).thenReturn(false);

        IncidentEntity incident = buildIncident();
        assertThat(guardrailService.isAllowed(incident, RemediationActionType.POD_SCALE_HORIZONTAL)).isTrue();
    }

    @Test
    void isAllowed_returnsFalseWhenCooldownActive() {
        when(redisTemplate.hasKey(contains("POD_SCALE_HORIZONTAL"))).thenReturn(true);

        IncidentEntity incident = buildIncident();
        assertThat(guardrailService.isAllowed(incident, RemediationActionType.POD_SCALE_HORIZONTAL)).isFalse();
    }

    @Test
    void isAllowed_returnsFalseWhenRestartLimitReached() {
        when(redisTemplate.hasKey(contains("POD_RESTART"))).thenReturn(false);
        when(valueOperations.get(contains("restarts"))).thenReturn("3");

        IncidentEntity incident = buildIncident();
        assertThat(guardrailService.isAllowed(incident, RemediationActionType.POD_RESTART)).isFalse();
    }

    @Test
    void recordAction_setsCooldown() {
        IncidentEntity incident = buildIncident();
        guardrailService.recordAction(incident, RemediationActionType.POD_SCALE_HORIZONTAL);

        verify(valueOperations).set(contains("POD_SCALE_HORIZONTAL"), eq("1"), any());
    }

    private IncidentEntity buildIncident() {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(UUID.randomUUID());
        incident.setServiceName("test-service");
        incident.setSeverity(Severity.P2_HIGH);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setCorrelationRule("HIGH_CPU_SUSTAINED");
        return incident;
    }
}
