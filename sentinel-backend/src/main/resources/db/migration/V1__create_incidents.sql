-- SENTINEL: Incidents table
-- TimescaleDB hypertable on detected_at for time-series queries

CREATE TABLE IF NOT EXISTS incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_name VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    correlated_signals JSONB DEFAULT '[]',
    correlation_rule VARCHAR(255),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    auto_remediated BOOLEAN DEFAULT FALSE,
    resolved_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_incidents_service ON incidents(service_name);
CREATE INDEX IF NOT EXISTS idx_incidents_status ON incidents(status);
CREATE INDEX IF NOT EXISTS idx_incidents_severity ON incidents(severity);
CREATE INDEX IF NOT EXISTS idx_incidents_detected_at ON incidents(detected_at DESC);

-- Convert to TimescaleDB hypertable if TimescaleDB extension is available.
-- Hypertable conversion REQUIRES the partitioning column (detected_at) to be
-- part of every unique index, including the primary key. Our PK is `id` only
-- (single-column UUID, matched in JPA's IncidentEntity), so create_hypertable
-- raises TS103 here. We catch that and fall back to a regular table — the
-- application works identically; only time-series-specific optimisations are
-- lost. On non-Timescale Postgres the outer IF skips the block entirely.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        BEGIN
            PERFORM create_hypertable('incidents', 'detected_at', if_not_exists => TRUE);
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'create_hypertable(incidents) skipped: %', SQLERRM;
        END;
    END IF;
END $$;
