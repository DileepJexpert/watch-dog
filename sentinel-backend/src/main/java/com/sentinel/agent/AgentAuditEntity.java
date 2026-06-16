package com.sentinel.agent;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "agent_audit")
@Data
@NoArgsConstructor
public class AgentAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "question", columnDefinition = "text")
    private String question;

    @Column(name = "answer_summary", columnDefinition = "text")
    private String answerSummary;

    @Column(name = "mode")
    private String mode;

    @Column(name = "model")
    private String model;

    @Column(name = "steps")
    private int steps;

    @Column(name = "input_tokens")
    private int inputTokens;

    @Column(name = "output_tokens")
    private int outputTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private List<Map<String, Object>> toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private List<Map<String, Object>> evidence;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
