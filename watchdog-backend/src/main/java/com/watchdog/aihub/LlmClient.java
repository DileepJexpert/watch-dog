package com.watchdog.aihub;

import com.watchdog.aihub.model.ChatMessage;
import com.watchdog.aihub.model.LlmOptions;
import com.watchdog.aihub.model.LlmResponse;
import com.watchdog.aihub.model.ToolSpec;

import java.util.List;

/**
 * FR-1: Provider-agnostic chat client. Switching base URL + model in config must
 * repoint the model with zero code changes.
 */
public interface LlmClient {
    LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, LlmOptions opts);
}
