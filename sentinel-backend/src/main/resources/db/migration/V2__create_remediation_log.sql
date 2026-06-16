-- SENTINEL: Remediation audit log

CREATE TABLE IF NOT EXISTS remediation_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID REFERENCES incidents(id) ON DELETE SET NULL,
    action_type VARCHAR(100) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    parameters JSONB DEFAULT '{}',
    outcome VARCHAR(20) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    executed_by VARCHAR(50) NOT NULL DEFAULT 'AUTO',
    failure_reason VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_remediation_incident ON remediation_log(incident_id);
CREATE INDEX IF NOT EXISTS idx_remediation_service ON remediation_log(service_name);
CREATE INDEX IF NOT EXISTS idx_remediation_executed_at ON remediation_log(executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_remediation_action_type ON remediation_log(action_type);
