package com.watchdog.knowledge;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "knowledge_doc")
@Data
@NoArgsConstructor
public class KnowledgeDocEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type")
    private String sourceType;

    private String title;

    @Column(columnDefinition = "text")
    private String content;

    /**
     * Embedding stored as JSONB array-of-double so it works regardless of
     * pgvector availability. When pgvector is enabled (per spec FR-4), this
     * column can be migrated to {@code vector(N)} without losing data.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Double> embedding;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
