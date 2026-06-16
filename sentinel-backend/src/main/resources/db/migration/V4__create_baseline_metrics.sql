-- SENTINEL: Baseline metrics for anomaly detection

CREATE TABLE IF NOT EXISTS baseline_metrics (
    id BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(255) NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    hour_of_day INT CHECK (hour_of_day >= 0 AND hour_of_day <= 23),
    day_of_week INT CHECK (day_of_week >= 1 AND day_of_week <= 7),
    mean DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    std_dev DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    sample_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (service_name, metric_name, hour_of_day, day_of_week)
);

CREATE INDEX IF NOT EXISTS idx_baseline_service_metric ON baseline_metrics(service_name, metric_name);
CREATE INDEX IF NOT EXISTS idx_baseline_time_slot ON baseline_metrics(hour_of_day, day_of_week);
