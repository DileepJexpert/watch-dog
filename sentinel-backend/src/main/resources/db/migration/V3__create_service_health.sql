-- SENTINEL: Current service health state

CREATE TABLE IF NOT EXISTS service_health (
    service_name VARCHAR(255) PRIMARY KEY,
    status VARCHAR(10) NOT NULL DEFAULT 'GREEN',
    error_rate DOUBLE PRECISION DEFAULT 0.0,
    latency_p95 DOUBLE PRECISION DEFAULT 0.0,
    latency_p99 DOUBLE PRECISION DEFAULT 0.0,
    request_rate DOUBLE PRECISION DEFAULT 0.0,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active_incident_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_service_health_status ON service_health(status);
