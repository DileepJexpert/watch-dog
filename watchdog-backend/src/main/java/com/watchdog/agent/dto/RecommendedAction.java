package com.watchdog.agent.dto;

public record RecommendedAction(String action, String rationale, boolean requiresApproval) {
    public static RecommendedAction advisory(String action, String rationale) {
        return new RecommendedAction(action, rationale, true);
    }
}
