package com.sentinel.agent;

import com.sentinel.agent.dto.AgentAnswer;
import com.sentinel.agent.dto.AgentEvidence;
import com.sentinel.aihub.model.ToolCall;
import com.sentinel.aihub.model.Usage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuditService {

    private final AgentAuditRepository repository;

    @Transactional
    public void record(String sessionId, String question, AgentAnswer answer,
                       String mode, String model, int steps, Usage usage,
                       List<ToolCall> allToolCalls, String error) {
        try {
            AgentAuditEntity e = new AgentAuditEntity();
            e.setSessionId(sessionId);
            e.setQuestion(question);
            e.setAnswerSummary(answer == null ? null : answer.summary());
            e.setMode(mode);
            e.setModel(model);
            e.setSteps(steps);
            if (usage != null) {
                e.setInputTokens(usage.inputTokens());
                e.setOutputTokens(usage.outputTokens());
            }
            e.setToolCalls(toolCallsToJson(allToolCalls));
            e.setEvidence(evidenceToJson(answer == null ? List.of() : answer.evidence()));
            e.setError(error);
            repository.save(e);
        } catch (Exception ex) {
            log.warn("Failed to persist agent audit record: {}", ex.getMessage());
        }
    }

    private List<Map<String, Object>> toolCallsToJson(List<ToolCall> calls) {
        if (calls == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(calls.size());
        for (ToolCall c : calls) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("args", c.args() == null ? Map.of() : c.args());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> evidenceToJson(List<AgentEvidence> evidence) {
        if (evidence == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(evidence.size());
        for (AgentEvidence ev : evidence) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", ev.source());
            m.put("ref", ev.ref());
            m.put("excerpt", ev.excerpt());
            out.add(m);
        }
        return out;
    }
}
