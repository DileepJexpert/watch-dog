-- SENTINEL AI: knowledge base for runbooks and resolved incidents (FR-4).
--
-- The embedding is stored as JSONB so the schema works regardless of whether
-- pgvector is installed. The Java retrieval path computes cosine similarity in
-- memory, which is sufficient for the demo / pilot footprint.
--
-- Production upgrade path: once pgvector is provisioned, run
--   ALTER TABLE knowledge_doc
--     ALTER COLUMN embedding TYPE vector(1024)
--     USING embedding::text::vector;
-- and set KNOWLEDGE_PGVECTOR_ENABLED=true to switch retrieval to native vector
-- ops with an ivfflat / hnsw index.

CREATE TABLE IF NOT EXISTS knowledge_doc (
    id          BIGSERIAL PRIMARY KEY,
    source_type TEXT,                       -- 'runbook' | 'incident'
    title       TEXT,
    content     TEXT,
    embedding   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_source_type ON knowledge_doc(source_type);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_created_at ON knowledge_doc(created_at DESC);

-- Attempt to enable pgvector; swallow the error so this migration succeeds on
-- environments that don't ship the extension (typical for the dev cluster).
DO $$
BEGIN
    BEGIN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS vector';
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pgvector not available; using JSONB embeddings + in-memory cosine search';
    END;
END $$;
