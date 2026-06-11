package com.watchdog.agent.dto;

import com.watchdog.aihub.model.ChatMessage;

import java.util.List;

public record AgentAskRequest(
        String question,
        List<ChatMessage> history,
        String sessionId,
        String serviceContext
) {
}
