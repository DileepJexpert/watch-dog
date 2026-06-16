package com.sentinel.agent.dto;

import com.sentinel.aihub.model.ChatMessage;

import java.util.List;

public record AgentAskRequest(
        String question,
        List<ChatMessage> history,
        String sessionId,
        String serviceContext
) {
}
