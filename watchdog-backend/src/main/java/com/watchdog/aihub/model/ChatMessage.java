package com.watchdog.aihub.model;

import java.util.List;

public record ChatMessage(
        ChatRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {
    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, null, toolCallId);
    }
}
