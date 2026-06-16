# WATCHDOG — Unified API Monitoring & Auto-Remediation Platform

> **Codename: WATCHDOG** — Integrating Kibana · Jaeger · Grafana into a Single Intelligent Monitoring Engine

## Overview

WATCHDOG is a Java Spring Boot platform that:
- **Ingests** data from Elasticsearch/Kibana (logs), Jaeger (traces), and Grafana/Prometheus (metrics)
- **Correlates** signals across sources using a sliding-window engine with 20 predefined rules
- **Detects** anomalies using Z-score statistical models
- **Alerts** via Slack, Email, PagerDuty, and OpsGenie with tiered severity routing
- **Remediates** known issues automatically (pod scale, restart, rollback, circuit breaker)
- **Displays** everything in a React dashboard — the single pane of glass

## Business Impact

| Metric | Before (Manual) | With WATCHDOG |
|--------|----------------|---------------|
| Dedicated monitoring staff | 4–5 FTEs | 0 (on-call model) |
| Mean time to detect | 15–45 min | &lt; 2 min |
| Mean time to resolve | 30–60 min | &lt; 5 min (auto) |

## Architecture

```
Layer 1: Ingestion       → Elasticsearch, Jaeger, Grafana, Health Probes
Layer 2: Processing      → Event normalization, Kafka streaming
Layer 3: Intelligence    → Correlation engine, Z-score anomaly detection, rule engine
Layer 4: Action          → Auto-remediation (K8s), alerting, dashboard
```

## Quick Start (Docker Compose)

The compose file lives next to the backend code (`watchdog-backend/docker-compose.yml`)
so IntelliJ's Services tab can run it from inside the project module.

```bash
# Start all services (PostgreSQL, Redis, Kafka, Elasticsearch, Jaeger, Grafana,
# watchdog-backend, watchdog-frontend)
cd watchdog-backend
docker compose up -d

# Access WATCHDOG Dashboard
open http://localhost:3000

# Access WATCHDOG API
curl http://localhost:8080/api/dashboard/summary
```

From IntelliJ: open `watchdog-backend/docker-compose.yml`, click the green ▶
in the gutter → "Run docker-compose.yml". The Services tool window lets you
start/stop individual containers.

## Project Structure

```
watchdog-backend/           # Spring Boot 3.x backend
  src/main/java/com/watchdog/
    ingestion/              # Elasticsearch, Jaeger, Grafana connectors
    correlation/            # Correlation engine + 20 rules
    intelligence/           # Static rule engine + Z-score anomaly detection
    remediation/            # Auto-remediation with K8s client + guardrails
    alerting/               # Slack, Email, PagerDuty, OpsGenie
    api/                    # REST API + WebSocket controllers
    scheduler/              # Weekly digest + anomaly retraining
  src/main/resources/
    application.yml         # Configuration
    rules/default-rules.yml # 10 static YAML alert rules

watchdog-frontend/          # React + TypeScript dashboard
  src/
    components/             # ServiceHealthMap, ActiveIncidents, LatencyHeatmap, etc.
    hooks/                  # useWebSocket (real-time updates)

k8s/                              # Kubernetes manifests
watchdog-backend/docker-compose.yml   # Local development environment (next to backend code)
```

## Configuration

All config is environment-variable driven:

| Variable | Description | Default |
|----------|-------------|---------|
| `ES_URL` | Elasticsearch URL | `http://localhost:9200` |
| `JAEGER_URL` | Jaeger Query API URL | `http://localhost:16686` |
| `GRAFANA_URL` | Grafana URL | `http://localhost:3000` |
| `GRAFANA_API_KEY` | Grafana API key | — |
| `SLACK_WEBHOOK_URL` | Slack incoming webhook | — |
| `PAGERDUTY_KEY` | PagerDuty integration key | — |
| `REMEDIATION_DRY_RUN` | Dry run mode (no K8s actions) | `true` |

## Correlation Rules (20 predefined)

1. Memory Leak / Resource Exhaustion · 2. Database Connectivity Issue · 3. Cascading Failure
4. CrashLoop / Unstable Deployment · 5. High HTTP Error Rate · 6. Latency Degradation
7. TLS Certificate Expiry · 8. Traffic Anomaly · 9. Pod OOMKill · 10. Deployment Regression
11. Service Down · 12. High CPU Sustained · 13. Upstream Dependency Failure
14. Slow Database Queries · 15. Circuit Breaker Triggered · 16. Low Disk Space
17. Message Queue Backlog · 18. Connection Pool Exhaustion · 19. External API Throttling
20. Security Anomaly / Auth Failure Spike

## Technology Stack

Java 17 · Spring Boot 3.2 · Spring Kafka · PostgreSQL + TimescaleDB · Redis · Fabric8 K8s Client · React 18 · TypeScript · Recharts

---

## AI Layer (Copilot)

The AI / agent layer extends the deterministic engine above with natural-language retrieval, RAG, LLM-assisted RCA, and intelligent log analysis. It is **disabled by default** and is **decision-support only** — recommendations are surfaced; remediation is never executed automatically.

### Architecture

```
Layer 1: aihub          → LlmClient abstraction (Anthropic /v1/messages compatible)
Layer 2: agent          → Tool-calling orchestrator + 10 tools wrapping existing services
Layer 3: knowledge      → RAG (runbooks + resolved incidents) via pgvector or JSONB fallback
Layer 4: ingestion (new)→ DatabaseConnector (read-only SQL) + RuntimeMetricsConnector (Actuator/APM)
Layer 5: api/frontend   → /api/agent/ask, /ws/agent stream, AgentConsole UI
```

### Agent tools (10)

| Tool | Wraps | Mutating |
| --- | --- | --- |
| `search_logs`           | ElasticsearchConnector             | no |
| `summarize_logs`        | ElasticsearchConnector + signature bucketing | no |
| `get_traces`            | JaegerConnector                     | no |
| `query_metrics`         | GrafanaConnector / PromQL           | no |
| `correlate`             | SlidingWindowBuffer + 20 rules      | no |
| `detect_anomalies`      | ZScoreDetector                      | no |
| `pod_status`            | KubernetesClientWrapper (read-only) | no |
| `query_database`        | DatabaseConnector (SELECT-only)     | no |
| `get_runtime_metrics`   | RuntimeMetricsConnector             | no |
| `search_knowledge`      | KnowledgeService (RAG)              | no |
| `finalize_answer`       | FR-9 output contract                | n/a |

### Enable

```bash
# 1. Provision the AI layer config
export AGENT_ENABLED=true
export AGENT_MODE=advisory                  # advisory | assisted (advisory is the hard default)
export AGENT_MAX_STEPS=8
export LLM_BASE_URL=https://ai-hub.prod-dev.idfcfirstbank.com  # repoint via config only
export LLM_API_KEY=<secret>
export LLM_MODEL=<model id>

# 2. Optional: knowledge base + runtime + DB
export KNOWLEDGE_EMBEDDING_BASE_URL=...     # OpenAI-compatible /v1/embeddings (optional; hash fallback otherwise)
export APM_URL=http://payments-svc          # default APM source for get_runtime_metrics

# 3. Optional: enable LLM-RCA on every fired correlation rule + proactive scan
export AGENT_RCA_ON_CORRELATION=true
export AGENT_PROACTIVE_SCAN_SECONDS=300
```

Switching providers (e.g. dev → IDFC AI Hub) is a config-only change — `LlmClient` is the seam (FR-1).

### Endpoints

- `POST /api/agent/ask` — synchronous: returns the FR-9 `AgentAnswer`
- `POST /api/agent/ask/stream` — fires the agent loop and streams steps on `/topic/agent/{sessionId}` via `/ws/agent`
- `POST /api/agent/knowledge` — ingest a runbook (`{title, content}`)
- `GET  /api/agent/status` — config probe used by the UI

### FR-9 answer schema

```json
{
  "summary": "string",
  "rootCause": { "hypothesis": "string", "confidence": "low|medium|high" },
  "evidence": [ { "source": "logs|traces|metrics|db|runtime|knowledge|correlation|anomaly|pod", "ref": "...", "excerpt": "..." } ],
  "recommendedActions": [ { "action": "...", "rationale": "...", "requiresApproval": true } ]
}
```

### Hard safety properties

- **Advisory gating (FR-8)** — mutating tools are filtered out of the agent's tool list when `AGENT_MODE=advisory`. `REMEDIATION_DRY_RUN=true` stays as the hard default and is the second gate.
- **Audit (NFR)** — every agent run lands in `agent_audit` (question, tool calls, args, evidence, model, mode, step count, error).
- **PII redaction (NFR)** — tool outputs are redacted (regex + blocked-field allow-list) before being sent to the LLM. v1 is a placeholder for the bank's stronger redactor.
- **Token control (NFR)** — `summarize_logs` pre-aggregates noisy log windows so raw lines never go to the model; `LLM_MAX_TOKENS` caps each call.
- **Backwards compatibility** — with `AGENT_ENABLED=false` (the default), every existing component (dashboard, correlation, anomaly, alerting, remediation) runs unchanged.

### Configuration reference (additions)

| Variable | Description | Default |
|----------|-------------|---------|
| `AGENT_ENABLED` | Master switch for the AI layer | `false` |
| `AGENT_MODE` | `advisory` \| `assisted` | `advisory` |
| `AGENT_MAX_STEPS` | Tool-calling loop cap | `8` |
| `AGENT_RCA_ON_CORRELATION` | Fire LLM-RCA when a correlation rule fires (FR-7) | `false` |
| `AGENT_PROACTIVE_SCAN_SECONDS` | Proactive scan interval; 0 disables | `0` |
| `AGENT_REDACTION_ENABLED` | Redact tool outputs before sending to model | `true` |
| `LLM_PROVIDER` | Provider key | `anthropic` |
| `LLM_BASE_URL` | Model endpoint | — |
| `LLM_API_KEY` | Auth | — |
| `LLM_MODEL` | Model id | — |
| `LLM_MAX_TOKENS` | Cap per call | `2000` |
| `LLM_TIMEOUT_MS` | Request timeout | `30000` |
| `KNOWLEDGE_EMBEDDING_MODEL` | Embedding model id | — |
| `KNOWLEDGE_EMBEDDING_BASE_URL` | Embedding endpoint (OpenAI-compatible) | — |
| `KNOWLEDGE_EMBEDDING_DIMENSION` | Vector dimension | `1024` |
| `KNOWLEDGE_PGVECTOR_ENABLED` | Flip on once `pgvector` extension is provisioned | `false` |
| `APM_URL` | Default runtime/APM source | — |
