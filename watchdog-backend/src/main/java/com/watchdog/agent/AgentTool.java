package com.watchdog.agent;

import com.watchdog.agent.dto.AgentEvidence;

import java.util.List;
import java.util.Map;

/**
 * FR-2 tool contract. Implementations wrap an existing service so the agent loop
 * can call it. Each tool MUST self-declare its JSON Schema so the LLM gets
 * accurate argument hints.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema (object) describing the tool's arguments. */
    Map<String, Object> jsonSchema();

    /**
     * Execute the tool. The result holds both a model-facing payload and the
     * structured evidence the orchestrator will return to the caller (FR-9).
     */
    ToolResult execute(Map<String, Object> args);

    /**
     * Tools that mutate state must override this and return false in
     * {@link AgentMode#ADVISORY}. The orchestrator filters them out at register
     * time when mode is advisory (FR-8 advisory gating).
     */
    default boolean isMutating() {
        return false;
    }

    /**
     * Tool execution outcome.
     *
     * @param payload    JSON-serialisable result the agent will reason over
     * @param evidence   evidence records that produced this payload (for FR-9 citation)
     * @param error      optional error string; when non-null the tool failed
     */
    record ToolResult(Object payload, List<AgentEvidence> evidence, String error) {
        public static ToolResult ok(Object payload, List<AgentEvidence> evidence) {
            return new ToolResult(payload, evidence == null ? List.of() : evidence, null);
        }
        public static ToolResult error(String message) {
            return new ToolResult(Map.of("error", message), List.of(), message);
        }
        public boolean isError() {
            return error != null;
        }
    }
}
