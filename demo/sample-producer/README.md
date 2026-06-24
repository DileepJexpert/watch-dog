# SENTINEL sample producer

The smallest possible signal generator for SENTINEL. **No Maven, no Spring Boot,
no Filebeat needed** — a single Python 3 file using only stdlib.

| Signal | How |
|---|---|
| **Logs** | Direct POST to Elasticsearch `/_bulk` in ECS shape |
| **Traces** | Direct OTLP/HTTP POST to Jaeger `/v1/traces` |
| **Metrics** | Prometheus `/metrics` endpoint on `:1882` (scraped by Prometheus) |

This is the "I just want signals flowing in one command" tool. The fuller
`demo/sample-app/` Spring Boot service is more realistic but takes more setup;
use this when you want to verify the SENTINEL pipeline without touching JVM
land.

## Prereqs

- Python 3.7+
- SENTINEL infra running (`docker compose -f sentinel-backend/docker-compose.yml up -d`)

That's it. No `pip install` needed.

## Run

```powershell
# From the repo root, in PowerShell:
cd C:\dileepkm\Learning\watch-dog\demo\sample-producer
.\run.ps1

# Or fully parameterized:
.\run.ps1 -ServiceName payments-svc -RatePerSec 5 -DurationSec 120
```

Or directly with Python (Linux / macOS / Git Bash):

```bash
cd demo/sample-producer
python3 producer.py
```

You'll see:

```
[producer] starting — service=sample-producer rate=2/s logs=True traces=True metrics=True
[producer] ES=http://localhost:9201  OTLP=http://localhost:4318/v1/traces
[producer:metrics] /metrics listening on :1882
```

`Ctrl+C` to stop. Final line prints the totals.

## What you should see downstream

After ~30 seconds of running:

### Elasticsearch (Kibana)

```powershell
Invoke-RestMethod "http://localhost:9201/logs-*/_count?q=service.name:sample-producer"
# expect count > 0 and growing

# Open Kibana, filter by service.name = "sample-producer"
Start-Process http://localhost:5602
```

### Jaeger (traces)

```powershell
Invoke-RestMethod "http://localhost:16687/api/services" | Select-Object -ExpandProperty data
# expect: includes "sample-producer"

Start-Process http://localhost:16687
```

### Prometheus

The producer self-exposes `/metrics`. For Prometheus to scrape it, add this
job to `demo/prometheus.yml` then `docker compose restart prometheus`:

```yaml
  - job_name: 'sample-producer'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['host.docker.internal:1882']
        labels:
          service: sample-producer
```

Or hit the endpoint directly to confirm it's serving:

```powershell
Invoke-RestMethod http://localhost:1882/metrics
# sample_producer_events_total{service="sample-producer"} 42
# sample_producer_logs_sent_total{service="sample-producer"} 42
# http_server_requests_total{service="sample-producer",log_level="INFO",status="200"} 21
# ...
```

### Grafana

```powershell
Start-Process http://localhost:3001
# Explore -> Prometheus datasource
# Try:  sample_producer_logs_sent_total
# Or:   rate(http_server_requests_total{service="sample-producer",log_level="ERROR"}[1m])
```

### SENTINEL dashboard

After a minute of running at 2/s the producer will have generated enough ERROR
logs that `HIGH_ERROR_RATE` and `DB_CONNECTIVITY` should fire. Watch your IntelliJ
console for the breadcrumb sequence:

```
[ingest:es]      poll OK — hits.total=N parsed events=M services=[sample-producer]
[correlation:event]  service=sample-producer type=LOG severity=P2_HIGH ...
[correlation:fire]   rule=HIGH_ERROR_RATE service=sample-producer
[incident:ws-push]   pushed incident ... to /topic/incidents
```

## Configuration (env vars)

| Var | Default | Purpose |
|---|---|---|
| `ES_URL` | `http://localhost:9201` | Elasticsearch base URL |
| `PROM_PORT` | `1882` | Local port the `/metrics` endpoint binds to |
| `OTLP_URL` | `http://localhost:4318/v1/traces` | Jaeger OTLP/HTTP endpoint |
| `SERVICE_NAME` | `sample-producer` | Tags every signal with this service name |
| `RATE_PER_SEC` | `2` | Events per second |
| `DURATION_SEC` | `0` | Run forever (0) or stop after N seconds |
| `WITH_LOGS` | `true` | Set `false` to skip ES |
| `WITH_TRACES` | `true` | Set `false` to skip OTLP |
| `WITH_METRICS` | `true` | Set `false` to skip the `/metrics` server |

## Why use this vs. `demo/sample-app/`?

| | sample-producer (this) | sample-app |
|---|---|---|
| Runtime | Python 3 stdlib | JVM via Maven |
| Setup | None | `mvn spring-boot:run` |
| Filebeat needed | **No** — POSTs to ES directly | Yes — tails `logs/app.json` |
| How to invoke | Loop runs automatically | HTTP requests to `/fail`, `/slow`, etc. |
| Realism | Synthetic events only | Real Spring Boot service with controllers |
| When to use | Smoke-test the pipeline; CI; quick demos | Realistic E2E with a real service |

Both can run side by side — they appear as separate `service.name`s.
