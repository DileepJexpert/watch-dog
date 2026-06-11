package com.watchdog.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * FR-9 standardised answer payload returned by the agent (used by /api/agent/ask,
 * the WebSocket stream and alerting payloads). Frontend renders evidence +
 * recommended actions from this directly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentAnswer(
        String summary,
        RootCause rootCause,
        List<AgentEvidence> evidence,
        List<RecommendedAction> recommendedActions,
        List<Map<String, Object>> trace
) {
}
