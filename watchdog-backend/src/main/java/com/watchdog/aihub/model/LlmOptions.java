package com.watchdog.aihub.model;

public record LlmOptions(Integer maxTokens, Double temperature, String model) {
    public static LlmOptions defaults() {
        return new LlmOptions(null, null, null);
    }
}
