# WATCHDOG AI-Enhanced Architecture

## Table of Contents

1. [Platform Overview](#1-platform-overview)
2. [Architecture Flow Diagrams](#2-architecture-flow-diagrams)
3. [Requirements Mapping (FR-1 to FR-9)](#3-requirements-mapping)
4. [LLM Configuration — Two-Mode Switch](#4-llm-configuration--two-mode-switch)
5. [Component Reference](#5-component-reference)

---

## 1. Platform Overview

WATCHDOG is a real-time production-support observability platform with two layers:

| Layer | Purpose | Always On? |
|-------|---------|------------|
| **Deterministic Engine** | Ingest signals → correlate → alert → auto-remediate | Yes |
| **AI Layer (AIOps Copilot)** | Natural-language RCA, proactive scanning, knowledge base | Opt-in (`AGENT_ENABLED=true`) |

The AI layer is additive — the deterministic engine runs identically whether the AI layer is enabled or not.

---

## 2. Architecture Flow Diagrams

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          WATCHDOG PLATFORM                                  │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                    DATA INGESTION LAYER                              │   │
│  │                                                                      │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐ │   │
│  │  │ Elasticsearch│ │    Jaeger    │ │   Grafana    │ │  Health    │ │   │
│  │  │  Connector   │ │  Connector   │ │  Connector   │ │  Probes    │ │   │
│  │  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └─────┬──────┘ │   │
│  └─────────┼────────────────┼────────────────┼────────────────┼────────┘   │
│            │                │                │                │             │
│            ▼                ▼                ▼                ▼             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              EVENT NORMALIZATION → KAFKA BUS                        │   │
│  │         NormalizedEvent { service, type, severity, ... }            │   │
│  └─────────────────────────────┬───────────────────────────────────────┘   │
│                                │                                           │
│                                ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                  DETERMINISTIC ENGINE                                │   │
│  │                                                                      │   │
│  │  ┌────────────────┐    ┌──────────────────┐    ┌─────────────────┐  │   │
│  │  │ SlidingWindow  │───▶│ CorrelationEngine│───▶│ 20 Correlation  │  │   │
│  │  │ Buffer (Redis) │    │  (30s tick)      │    │ Rules           │  │   │
│  │  └────────────────┘    └────────┬─────────┘    └─────────────────┘  │   │
│  │                                 │                                    │   │
│  │                                 ▼                                    │   │
│  │  ┌────────────────┐    ┌──────────────────┐    ┌─────────────────┐  │   │
│  │  │ Anomaly        │    │ IncidentEntity   │    │ Alerting        │  │   │
│  │  │ Detection      │    │ (PostgreSQL)     │    │ Service         │  │   │
│  │  │ (Z-Score)      │    └────────┬─────────┘    │ (Slack/PD/OG/  │  │   │
│  │  └────────────────┘             │              │  Teams/Email)   │  │   │
│  │                                 │              └─────────────────┘  │   │
│  │                                 ▼                                    │   │
│  │                        ┌──────────────────┐                         │   │
│  │                        │ Auto-Remediation │                         │   │
│  │                        │ Engine           │                         │   │
│  │                        │ (K8s actions,    │                         │   │
│  │                        │  dry-run default)│                         │   │
│  │                        └────────┬─────────┘                         │   │
│  └─────────────────────────────────┼───────────────────────────────────┘   │
│                                    │                                       │
│                    ┌───────────────┼──── (optional FR-7) ──────┐          │
│                    │               ▼                            │          │
│  ┌─────────────────┼───────────────────────────────────────────┼───────┐  │
│  │                 │       AI LAYER (OPT-IN)                   │       │  │
│  │                 │                                           │       │  │
│  │  ┌──────────────┴──┐  ┌──────────────┐  ┌──────────────┐  │       │  │
│  │  │  RCA Service    │  │   Agent      │  │  Knowledge   │  │       │  │
│  │  │  (FR-7: auto    │  │ Orchestrator │  │  Base (RAG)  │  │       │  │
│  │  │   RCA on new    │  │ (FR-2)       │  │  (FR-4)      │  │       │  │
│  │  │   incidents)    │  └──────┬───────┘  └──────────────┘  │       │  │
│  │  └─────────────────┘         │                             │       │  │
│  │                              ▼                             │       │  │
│  │  ┌───────────────────────────────────────────────────┐    │       │  │
│  │  │               10 AGENT TOOLS                      │    │       │  │
│  │  │  search_logs │ summarize_logs │ get_traces        │    │       │  │
│  │  │  query_metrics │ correlate │ detect_anomalies     │    │       │  │
│  │  │  pod_status │ query_database │ get_runtime_metrics│    │       │  │
│  │  │  search_knowledge                                 │    │       │  │
│  │  └───────────────────────────────────────────────────┘    │       │  │
│  │                              │                             │       │  │
│  │                              ▼                             │       │  │
│  │  ┌──────────────┐   ┌──────────────┐  ┌──────────────┐   │       │  │
│  │  │ PII Redactor │   │ Audit Trail  │  │ LLM Client   │───┘       │  │
│  │  │ (NFR)        │   │ (NFR)        │  │ (FR-1)       │           │  │
│  │  └──────────────┘   └──────────────┘  └──────┬───────┘           │  │
│  └──────────────────────────────────────────────┼────────────────────┘  │
│                                                 │                       │
└─────────────────────────────────────────────────┼───────────────────────┘
                                                  │
                                                  ▼
                                    ┌──────────────────────┐
                                    │    LLM ENDPOINT      │
                                    │                      │
                                    │  Option A: Company   │
                                    │   AI Hub (remote)    │
                                    │                      │
                                    │  Option B: Local     │
                                    │   model (Ollama)     │
                                    └──────────────────────┘
```

### 2.2 Deterministic Engine — Signal Flow

```
  Elasticsearch          Jaeger              Grafana            Health Probes
  (logs)                 (traces)            (metrics)          (HTTP/gRPC)
      │                    │                    │                    │
      ▼                    ▼                    ▼                    ▼
 ┌─────────┐         ┌─────────┐         ┌─────────┐         ┌─────────┐
 │ ES      │         │ Jaeger  │         │ Grafana │         │ Health  │
 │Connector│         │Connector│         │Connector│         │ Probe   │
 │ (poll)  │         │ (poll)  │         │ (poll)  │         │Service  │
 └────┬────┘         └────┬────┘         └────┬────┘         └────┬────┘
      │                    │                    │                    │
      └────────────────────┼────────────────────┼────────────────────┘
                           │
                           ▼
                  ┌──────────────────┐
                  │  Normalization   │     NormalizedEvent:
                  │  Service         │───▶ { service, type, severity,
                  └────────┬─────────┘       source, timestamp, payload }
                           │
                           ▼
                  ┌──────────────────┐
                  │   Kafka Topic    │
                  │  watchdog-events │
                  └────────┬─────────┘
                           │
                           ▼
                  ┌──────────────────┐
                  │  Correlation     │     Consumes events, stores in
                  │  Engine          │     Redis sliding window (ZSET),
                  │  (30s tick)      │     evaluates 20 rules every tick
                  └────────┬─────────┘
                           │
                   ┌───────┴───────┐
                   │ Match found?  │
                   └───┬───────┬───┘
                   No  │       │ Yes
                       │       ▼
                       │  ┌─────────────────┐
                       │  │ Create Incident │
                       │  │ (IncidentEntity)│
                       │  └────────┬────────┘
                       │           │
                       │           ├───▶ AlertingService (Slack / PagerDuty / OpsGenie / Teams / Email)
                       │           │
                       │           ├───▶ AutoRemediationEngine (dry-run by default)
                       │           │       │
                       │           │       ├── PodRestartAction
                       │           │       ├── PodScaleAction
                       │           │       ├── CircuitBreakerAction
                       │           │       ├── CacheFlushAction
                       │           │       └── DeploymentRollbackAction
                       │           │
                       │           ├───▶ WebSocket push to frontend (/topic/incidents)
                       │           │
                       │           └───▶ [Optional] RcaService (FR-7: AI root-cause analysis)
                       │
                       ▼
                    (wait for next tick)
```

### 2.3 AI Agent — Ask Flow (FR-2, FR-3)

```
  User asks: "Why is payment-service throwing 500s?"
      │
      ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  POST /api/agent/ask   (or /ask/stream via WebSocket)       │
  │  AgentController → AgentOrchestrator                        │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  STEP 1: Build System Prompt                                │
  │  - Inject available tool specs                              │
  │  - Include conversation history (capped at 20 messages)     │
  │  - Add safety instructions + output contract                │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  STEP 2: LLM Call (via LlmClient → Anthropic Messages API) │
  │  - Model decides which tools to call                        │
  │  - Returns tool_use blocks                                  │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  STEP 3: Execute Tools (parallel, 6-thread pool)            │
  │                                                             │
  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
  │  │ search_logs │  │ get_traces  │  │ query_metrics       │ │
  │  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
  │         │                │                     │            │
  │         ▼                ▼                     ▼            │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │  PII Redactor — scrub emails, tokens, card numbers  │   │
  │  └──────────────────────────────────────────────────────┘   │
  │         │                                                   │
  │         ▼                                                   │
  │  Collect tool_result blocks → evidence list                 │
  └──────────────────────────┬──────────────────────────────────┘
                             │
                      ┌──────┴──────┐
                      │ More tools  │──── Yes ──▶ (back to Step 2,
                      │ needed?     │              up to max_steps=8)
                      └──────┬──────┘
                             │ No (model calls finalize_answer)
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  STEP 4: Parse AgentAnswer (FR-9 contract)                  │
  │  {                                                          │
  │    summary: "...",                                          │
  │    rootCause: { description, confidence, service },         │
  │    evidence: [ { tool, query, snippet, timestamp } ],       │
  │    recommendedActions: [ { action, priority, safe } ],      │
  │    trace: [ { step, tool, input, output } ]                 │
  │  }                                                          │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ├──▶ AgentAuditService (write to agent_audit table)
                             │
                             ▼
                        Return to user
                    (REST JSON or WebSocket frames)
```

### 2.4 Knowledge Base — RAG Flow (FR-4)

```
  ┌──────────────────────┐         ┌───────────────────────────┐
  │  Ingest Runbook      │         │  Auto-capture resolved    │
  │  POST /api/agent/    │         │  incidents (title +       │
  │  knowledge           │         │  correlated_signals)      │
  └──────────┬───────────┘         └────────────┬──────────────┘
             │                                  │
             ▼                                  ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  EmbeddingClient                                            │
  │  - If LLM embedding endpoint configured → /v1/embeddings   │
  │  - Fallback → hash-based pseudo-embedding (for dev/test)    │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  knowledge_doc table (PostgreSQL)                           │
  │  - id, type, title, content, embedding (JSONB or pgvector) │
  │  - If pgvector enabled → vector column + cosine index       │
  │  - Otherwise → JSONB array + in-memory cosine similarity    │
  └──────────────────────────────────────────────────────────────┘
                             │
           (at query time)   │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  search_knowledge tool                                      │
  │  - Embed query → cosine similarity search → top-K results   │
  │  - Results injected into agent context as tool output        │
  └──────────────────────────────────────────────────────────────┘
```

---

## 3. Requirements Mapping

| Requirement | Description | Implementation | Key Files |
|-------------|-------------|----------------|-----------|
| **FR-1** | LLM Integration (provider-agnostic, env-driven) | `LlmClient` interface + `AnthropicLlmClient` (Anthropic Messages API). Swap model/provider via env vars only — zero code change. | `aihub/LlmClient.java`, `aihub/AnthropicLlmClient.java`, `aihub/AihubConfig.java` |
| **FR-2** | Agentic Orchestration (plan→act→observe loop) | `AgentOrchestrator` with step-cap loop, parallel tool dispatch (6 threads), `finalize_answer` tool for structured output. | `agent/AgentOrchestrator.java` |
| **FR-3** | Conversational Interface (REST + WebSocket) | `POST /api/agent/ask` (sync), `/ask/stream` (STOMP WebSocket), React `AgentConsole` with chat UI + evidence panel. | `api/AgentController.java`, `AgentConsole.tsx`, `useAgentSocket.ts` |
| **FR-4** | RAG Knowledge Base | Ingest runbooks + auto-capture resolved incidents → embed → cosine search → inject into agent context. pgvector-ready, works without it. | `knowledge/KnowledgeService.java`, `knowledge/DefaultEmbeddingClient.java` |
| **FR-5** | Deep Observability Tools | 10 tools: `search_logs`, `summarize_logs`, `get_traces`, `query_metrics`, `correlate`, `detect_anomalies`, `pod_status`, `query_database`, `get_runtime_metrics`, `search_knowledge`. | `agent/tools/*.java`, `ingestion/DatabaseConnector.java`, `ingestion/RuntimeMetricsConnector.java` |
| **FR-6** | Proactive Scanning | `ProactiveScanScheduler` — periodic agent sweep across active services (interval configurable, default off). | `scheduler/ProactiveScanScheduler.java` |
| **FR-7** | Auto-RCA on New Incidents | `RcaService` hooked into `CorrelationEngine` — when a new incident is created, optionally dispatch async LLM root-cause analysis. | `agent/RcaService.java`, `correlation/CorrelationEngine.java` |
| **FR-8** | Advisory Gating (safety) | `AGENT_MODE=advisory` filters mutating tools at orchestrator level. `REMEDIATION_DRY_RUN=true` is hard default. | `agent/AgentOrchestrator.java`, `agent/AgentMode.java` |
| **FR-9** | Structured Output Contract | `finalize_answer` tool enforces `AgentAnswer { summary, rootCause, evidence[], recommendedActions[], trace[] }` schema. | `agent/dto/AgentAnswer.java`, `agent/dto/*.java` |
| **NFR** | PII Redaction | Regex + blocked-field scrubbing on all tool outputs before LLM sees them. | `agent/PiiRedactor.java` |
| **NFR** | Audit Trail | Every agent run recorded: question, tool calls, evidence, model, mode, step count, timestamps. | `agent/AgentAuditService.java`, `V7__create_agent_audit.sql` |
| **NFR** | Dual Run Mode | IDE + infra Docker (`demo/docker-compose.infra-only.yml`) OR full Docker pipeline (`docker-compose.yml`). CI validates both. | `docker-compose.yml`, `demo/docker-compose.infra-only.yml`, `.github/workflows/ci.yml` |

### Correlation Rules (20)

```
HighErrorRateRule        LatencyDegradationRule     CascadingFailureRule
ServiceDownRule          CrashLoopRule              PodOOMKillRule
ConnectionPoolExhaustion SlowDatabaseQueryRule      MemoryLeakRule
CircuitBreakerOpenRule   DiskSpaceRule              DependencyFailureRule
DatabaseConnectivityRule MessageQueueBacklogRule    TrafficAnomalyRule
ThrottlingRule           SecurityAnomalyRule        TlsExpiryRule
DeploymentRegressionRule GracefulDegradationRule    HighCpuSustainedRule
```

---

## 4. LLM Configuration — Two-Mode Switch

The AI layer is designed so that switching between LLM endpoints is **env-var-only** — no code changes, no redeployment, no recompilation.

### How It Works

All LLM communication goes through a single interface:

```
LlmClient.chat(messages, tools, options)
       │
       ▼
AnthropicLlmClient  ──▶  HTTP POST to ${LLM_BASE_URL}/v1/messages
                          with header: x-api-key = ${LLM_API_KEY}
                          body: { model: ${LLM_MODEL}, ... }
```

The `AnthropicLlmClient` speaks the **Anthropic Messages API** envelope (`/v1/messages`). Any endpoint that accepts this format works — Anthropic direct, AWS Bedrock, company-hosted proxies, or local servers that emulate the same API (like Ollama with the Anthropic-compatible endpoint, or LiteLLM proxy).

### Option A: Company-Hosted Model (IDFC AI Hub / Production)

For models hosted inside your company network that require authentication:

**Environment variables:**

```bash
# --- AI Layer ON ---
AGENT_ENABLED=true
AGENT_MODE=advisory

# --- Company AI Hub endpoint ---
LLM_PROVIDER=anthropic
LLM_BASE_URL=https://your-company-ai-hub.internal.bank/api
LLM_API_KEY=your-company-api-key-here
LLM_MODEL=claude-sonnet-4-20250514
LLM_MAX_TOKENS=4000
LLM_TIMEOUT_MS=60000

# If your company hub requires a specific Anthropic version header:
LLM_ANTHROPIC_VERSION=2023-06-01
```

**Spring application-local.yml (alternative — drop this file next to application.yml):**

```yaml
watchdog:
  agent:
    enabled: true
    mode: advisory
  aihub:
    provider: anthropic
    base-url: https://your-company-ai-hub.internal.bank/api
    api-key: ${LLM_API_KEY}
    model: claude-sonnet-4-20250514
    max-tokens: 4000
    timeout-ms: 60000
```

**Run from IDE:**
```bash
# IntelliJ / VS Code — add these to your Run Configuration environment variables:
AGENT_ENABLED=true
LLM_BASE_URL=https://your-company-ai-hub.internal.bank/api
LLM_API_KEY=sk-company-xxxxxxxx
LLM_MODEL=claude-sonnet-4-20250514
```

### Option B: Local Model (Ollama — Test Today on Your Machine)

For testing immediately on your local machine with no API key or company access needed:

**Step 1 — Install and run Ollama:**

```bash
# Install Ollama (one-time)
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model (pick one based on your RAM):
ollama pull llama3.1        # 8B — needs ~8 GB RAM, fast
ollama pull mistral         # 7B — needs ~6 GB RAM, fast
ollama pull codellama       # 7B — good for code analysis
ollama pull llama3.1:70b    # 70B — needs ~48 GB RAM, best quality

# Ollama starts automatically and listens on http://localhost:11434
```

**Step 2 — Enable Ollama's OpenAI-compatible endpoint:**

Ollama natively exposes an OpenAI-compatible API at `http://localhost:11434/v1`. To make it work with WATCHDOG's Anthropic-envelope client, run a lightweight proxy:

**Option B1 — Using LiteLLM proxy (recommended, 1 command):**

```bash
# Install LiteLLM
pip install litellm[proxy]

# Start proxy — translates Anthropic Messages API → Ollama
litellm --model ollama/llama3.1 --port 4000 --drop_params
```

Then set:

```bash
AGENT_ENABLED=true
LLM_BASE_URL=http://localhost:4000
LLM_API_KEY=not-needed
LLM_MODEL=ollama/llama3.1
```

**Option B2 — Using Ollama's native OpenAI endpoint directly:**

If you switch `LLM_PROVIDER` to `openai` (or if a future OpenAI-compatible client is added), point directly:

```bash
AGENT_ENABLED=true
LLM_PROVIDER=anthropic
LLM_BASE_URL=http://localhost:11434
LLM_API_KEY=not-needed
LLM_MODEL=llama3.1
```

> Note: This requires the local server to accept the Anthropic Messages API format.
> LiteLLM (Option B1) handles this translation automatically.

**Spring application-local.yml for local testing:**

```yaml
watchdog:
  agent:
    enabled: true
    mode: advisory
    max-steps: 4           # lower for faster iteration with local models
  aihub:
    provider: anthropic
    base-url: http://localhost:4000    # LiteLLM proxy
    api-key: not-needed
    model: ollama/llama3.1
    max-tokens: 2000
    timeout-ms: 120000                 # local models are slower, increase timeout
```

### Quick-Switch Reference Card

| Setting | Option A: Company AI Hub | Option B: Local (Ollama + LiteLLM) |
|---------|-------------------------|-------------------------------------|
| `AGENT_ENABLED` | `true` | `true` |
| `LLM_BASE_URL` | `https://company-hub.internal/api` | `http://localhost:4000` |
| `LLM_API_KEY` | `sk-company-xxxxxxxx` | `not-needed` |
| `LLM_MODEL` | `claude-sonnet-4-20250514` | `ollama/llama3.1` |
| `LLM_MAX_TOKENS` | `4000` | `2000` |
| `LLM_TIMEOUT_MS` | `60000` | `120000` |
| `AGENT_MODE` | `advisory` | `advisory` |

### Switch in One Step

**From IDE (IntelliJ / VS Code):**
Edit your Run Configuration's environment variables — change only `LLM_BASE_URL`, `LLM_API_KEY`, and `LLM_MODEL`. Restart the app.

**From terminal:**
```bash
# Company hub
export LLM_BASE_URL=https://company-hub.internal/api LLM_API_KEY=sk-xxx LLM_MODEL=claude-sonnet-4-20250514

# Local model
export LLM_BASE_URL=http://localhost:4000 LLM_API_KEY=not-needed LLM_MODEL=ollama/llama3.1
```

Then run:
```bash
cd watchdog-backend
mvn spring-boot:run
```

**From Docker (production pipeline):**
Set the env vars in `docker-compose.yml` or pass them at deploy time:

```yaml
watchdog-backend:
  environment:
    AGENT_ENABLED: "true"
    LLM_BASE_URL: "https://company-hub.internal/api"
    LLM_API_KEY: "${LLM_API_KEY}"
    LLM_MODEL: "claude-sonnet-4-20250514"
```

---

## 5. Component Reference

### Backend Packages

```
com.watchdog
├── aihub/                    # FR-1: LLM client abstraction
│   ├── LlmClient.java       #   Interface
│   ├── AnthropicLlmClient.java  # Anthropic Messages API impl
│   ├── AihubConfig.java      #   Spring bean wiring
│   └── model/                #   ChatMessage, ToolCall, ToolSpec, etc.
│
├── agent/                    # FR-2, FR-7, FR-8, FR-9: Agent layer
│   ├── AgentOrchestrator.java    # Core plan→act→observe loop
│   ├── AgentTool.java            # Tool interface
│   ├── AgentMode.java            # ADVISORY / AUTONOMOUS enum
│   ├── PiiRedactor.java          # NFR: PII scrubbing
│   ├── AgentAuditService.java    # NFR: Audit trail
│   ├── RcaService.java           # FR-7: Auto-RCA on incidents
│   ├── dto/                      # AgentAnswer, AgentEvidence, etc.
│   └── tools/                    # FR-5: 10 agent tools
│       ├── SearchLogsTool.java
│       ├── SummarizeLogsTool.java
│       ├── GetTracesTool.java
│       ├── QueryMetricsTool.java
│       ├── CorrelateTool.java
│       ├── DetectAnomaliesTool.java
│       ├── PodStatusTool.java
│       ├── QueryDatabaseTool.java
│       ├── GetRuntimeMetricsTool.java
│       └── SearchKnowledgeTool.java
│
├── knowledge/                # FR-4: RAG knowledge base
│   ├── KnowledgeService.java
│   ├── EmbeddingClient.java
│   ├── DefaultEmbeddingClient.java
│   ├── KnowledgeDocEntity.java
│   └── KnowledgeRepository.java
│
├── api/                      # FR-3: REST + WebSocket endpoints
│   ├── AgentController.java
│   ├── DashboardController.java
│   ├── IncidentController.java
│   └── ...
│
├── correlation/              # Deterministic engine core
│   ├── CorrelationEngine.java    # Kafka consumer + rule evaluation
│   ├── SlidingWindowBuffer.java  # Redis ZSET window
│   └── rules/                    # 20 correlation rules
│
├── ingestion/                # Data connectors
│   ├── ElasticsearchConnector.java
│   ├── JaegerConnector.java
│   ├── GrafanaConnector.java
│   ├── HealthProbeService.java
│   ├── DatabaseConnector.java    # FR-5: read-only SQL
│   └── RuntimeMetricsConnector.java  # FR-5: Actuator metrics
│
├── alerting/                 # Notification channels
│   ├── AlertingService.java
│   ├── SlackNotifier.java
│   ├── TeamsNotifier.java
│   ├── PagerDutyNotifier.java
│   ├── OpsGenieNotifier.java
│   └── EmailNotifier.java
│
├── remediation/              # Auto-remediation (dry-run default)
│   ├── AutoRemediationEngine.java
│   ├── GuardrailService.java
│   └── actions/
│
├── intelligence/             # Anomaly detection + rule engine
│   ├── RuleEngine.java
│   └── anomaly/
│
├── scheduler/                # Scheduled tasks
│   ├── ProactiveScanScheduler.java   # FR-6
│   ├── AnomalyTrainingScheduler.java
│   └── DigestScheduler.java
│
└── config/                   # Spring configuration
    ├── WatchdogProperties.java   # All config as typed Java
    ├── WebSocketConfig.java      # STOMP /ws/agent
    └── ...
```

### Frontend Components

```
watchdog-frontend/src/
├── components/
│   └── AgentConsole.tsx      # FR-3: Chat UI with evidence panel
├── hooks/
│   └── useAgentSocket.ts     # WebSocket STOMP client
├── types/
│   └── agent.ts              # TypeScript types for AgentAnswer
└── App.tsx                   # Navigation with "AI Copilot" tab
```

### Infrastructure

```
docker-compose.yml                    # Full-stack (CI/pipeline)
demo/docker-compose.infra-only.yml    # Infra-only (local dev from IDE)
demo/docker-compose.local-app.yml     # Full stack + host app monitoring
.github/workflows/ci.yml             # 4-job CI pipeline
```

### Database Migrations

```
V1__create_incidents.sql       # Incidents table (TimescaleDB-tolerant)
V6__create_knowledge_doc.sql   # Knowledge base (pgvector-ready)
V7__create_agent_audit.sql     # Agent audit trail
```
