package com.watchdog.knowledge;

import com.watchdog.config.WatchdogProperties;
import com.watchdog.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * FR-4 knowledge service.
 *
 * Ingestion: runbooks + resolved incidents.
 * Retrieval: top-k cosine similarity. When pgvector is enabled in config the
 * service can be switched to native vector search; current default is
 * in-memory cosine over JSONB-stored embeddings, which works without any
 * Postgres extension.
 *
 * Capture loop: {@link #captureResolvedIncident(IncidentEntity, String, String)}
 * is the hook for the "symptom → cause → fix" record once an incident closes.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KnowledgeService {

    static final String SOURCE_RUNBOOK = "runbook";
    static final String SOURCE_INCIDENT = "incident";

    private final KnowledgeRepository repository;
    private final EmbeddingClient embeddingClient;
    private final WatchdogProperties properties;

    @Transactional
    public KnowledgeDocEntity ingestRunbook(String title, String content) {
        return persist(SOURCE_RUNBOOK, title, content);
    }

    @Transactional
    public KnowledgeDocEntity captureResolvedIncident(IncidentEntity incident, String cause, String fix) {
        String title = "Incident " + incident.getId() + " — " + incident.getTitle();
        StringBuilder body = new StringBuilder();
        body.append("Service: ").append(incident.getServiceName()).append("\n");
        body.append("Severity: ").append(incident.getSeverity()).append("\n");
        body.append("Symptom: ").append(incident.getTitle()).append("\n");
        if (incident.getCorrelationRule() != null) {
            body.append("Correlation rule: ").append(incident.getCorrelationRule()).append("\n");
        }
        body.append("Cause: ").append(cause == null ? "(not captured)" : cause).append("\n");
        body.append("Fix: ").append(fix == null ? "(not captured)" : fix);
        return persist(SOURCE_INCIDENT, title, body.toString());
    }

    private KnowledgeDocEntity persist(String sourceType, String title, String content) {
        KnowledgeDocEntity entity = new KnowledgeDocEntity();
        entity.setSourceType(sourceType);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setEmbedding(embeddingClient.embed(title + "\n" + content));
        return repository.save(entity);
    }

    public List<RetrievalResult> search(String query, int topK) {
        int k = topK > 0 ? topK : properties.getKnowledge().getTopK();
        List<Double> qEmbedding = embeddingClient.embed(query);
        List<KnowledgeDocEntity> all = repository.findAll();

        List<RetrievalResult> scored = new ArrayList<>(all.size());
        for (KnowledgeDocEntity doc : all) {
            if (doc.getEmbedding() == null || doc.getEmbedding().isEmpty()) continue;
            double sim = cosineSimilarity(qEmbedding, doc.getEmbedding());
            scored.add(new RetrievalResult(doc, sim));
        }
        scored.sort(Comparator.comparingDouble(RetrievalResult::score).reversed());
        return scored.size() > k ? scored.subList(0, k) : scored;
    }

    static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int n = Math.min(a.size(), b.size());
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < n; i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record RetrievalResult(KnowledgeDocEntity doc, double score) { }
}
