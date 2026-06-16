-- SENTINEL: Self-service alert rule builder

CREATE TABLE IF NOT EXISTS alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    service_name VARCHAR(255),
    condition_yaml TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_alert_rules_service ON alert_rules(service_name);
CREATE INDEX IF NOT EXISTS idx_alert_rules_enabled ON alert_rules(enabled);
