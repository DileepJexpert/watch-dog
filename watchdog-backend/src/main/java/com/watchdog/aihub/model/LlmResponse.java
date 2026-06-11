package com.watchdog.aihub.model;

import java.util.List;

public record LlmResponse(String text, List<ToolCall> toolCalls, Usage usage, String stopReason) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
