-- SENTINEL AI: per-run audit log (NFR auditability).
-- Required before any IDFC / prod use of the agent. Persists question, tool
-- calls + args, model + mode + step count, evidence, and any error.

CREATE TABLE IF NOT EXISTS agent_audit (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64),
    question        TEXT,
    answer_summary  TEXT,
    mode            VARCHAR(32),
    model           VARCHAR(255),
    steps           INT NOT NULL DEFAULT 0,
    input_tokens    INT NOT NULL DEFAULT 0,
    output_tokens   INT NOT NULL DEFAULT 0,
    tool_calls      JSONB DEFAULT '[]',
    evidence        JSONB DEFAULT '[]',
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_audit_created_at ON agent_audit(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_audit_session ON agent_audit(session_id);
