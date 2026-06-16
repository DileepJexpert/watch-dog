# SENTINEL — two ways to run it

```
                  ┌─────────────────────────────┐
                  │  Pick your run mode         │
                  └──────────┬──────────────────┘
                             │
        ┌────────────────────┴───────────────────┐
        │                                        │
        ▼                                        ▼
┌──────────────────────┐                  ┌──────────────────────┐
│  LOCAL DEV (IDE)     │                  │  PIPELINE / FULL DOCKER│
│                      │                  │                      │
│  Infra in docker,    │                  │  Everything in docker,│
│  sentinel-backend +  │                  │  including sentinel. │
│  frontend in your    │                  │                      │
│  IDE (F5 / npm run)  │                  │  What CI runs.       │
│                      │                  │                      │
│  Best for: reading   │                  │  Best for: shipping, │
│  the code, setting   │                  │  reproducible boot,  │
│  breakpoints,        │                  │  smoke testing.      │
│  hot-reload          │                  │                      │
└──────────────────────┘                  └──────────────────────┘
        │                                        │
        ▼                                        ▼
demo/docker-compose.infra-only.yml          sentinel-backend/docker-compose.yml
+ run backend with mvn spring-boot:run      (no extra steps — IntelliJ
+ run frontend with npm run dev              Services tab can run it)
```

Both modes are first-class — pick whichever fits the task, switch any time.

| Scenario | Use this | Walkthrough |
|---|---|---|
| **LOCAL DEV** — run SENTINEL from VS Code / IntelliJ, only infra in docker | `demo/docker-compose.infra-only.yml` | Scenario C below |
| **PIPELINE / FULL DOCKER** — every container builds and starts via `docker compose up` | `sentinel-backend/docker-compose.yml` | Scenario D below |
| Monitor *your own* Spring Boot app with SENTINEL also in docker | `demo/docker-compose.local-app.yml` + `demo/spring-boot-app-setup.md` | Scenario B below |
| Pure SENTINEL with synthetic data (no real app required) | `sentinel-backend/docker-compose.yml` + the seeder scripts | Scenarios 1–4 below |

Where each mode lives in CI:
- **Backend unit tests** + **frontend build** run on every push (`backend-tests`, `frontend-build` jobs in `.github/workflows/ci.yml`)
- **Pipeline path** is verified by the `docker-build` and `compose-smoke` jobs, which build both images from the repo-root context and boot the full stack to hit `/actuator/health` and `/api/agent/status`. If those jobs go red, the docker path is broken.

---

## Scenario A — pure SENTINEL with synthetic data

End-to-end walkthrough for verifying — on your laptop, with Docker — that:

1. SENTINEL is up and the infra it depends on (Postgres, Redis, Kafka, Elasticsearch, Jaeger, Prometheus, Grafana) is reachable
2. SENTINEL ingests ERROR logs from Elasticsearch and fires correlation rules
3. The AI Copilot can answer questions about a live incident using its tools
4. The FR-9 evidence-cited answer renders correctly in the dashboard

The scripts live in this folder. They use only `curl` + `python3` — no extra installs.

## 0. One-time setup

```bash
cd /path/to/sentinel/sentinel-backend

# Pull + start everything (postgres, redis, kafka, ES, kibana, jaeger,
# prometheus, grafana, sentinel backend, sentinel frontend).
docker compose up -d --build

# Confirm reachability (HTTP codes should all be 200, or 404 for grafana root).
cd .. && ./demo/check.sh
```

> Tip — IntelliJ users: open `sentinel-backend/docker-compose.yml` and click
> the green ▶ in the gutter to bring up the entire stack in one click.

Open the dashboard at <http://localhost:3000>. The "AI Copilot" tab will say the
agent is **disabled** at this point — that's expected.

## 1. Verify the deterministic engine works

```bash
# Push 15 fake ERROR logs into Elasticsearch for the payments-svc service.
./demo/seed-error-logs.sh payments-svc 15

# Wait ~30s (one ES poll interval), then re-check.
sleep 35
./demo/check.sh
```

What to look for:
- `Recent ERROR logs visible to SENTINEL` shows 15 entries under `payments-svc`
- `Open incidents` lists a new incident — likely `MEMORY_LEAK`, `DB_CONNECTIVITY`,
  or `HIGH_ERROR_RATE` depending on which message strings hit which rule
- The dashboard's "Active Incidents" panel updates in real time via WebSocket

You can repeat with different services / counts:
```bash
./demo/seed-error-logs.sh auth-svc 20
./demo/seed-error-logs.sh orders-svc 5
```

## 2. Turn on the AI Copilot (with a mock LLM)

The mock LLM is a tiny Python stdlib HTTP server that speaks the Anthropic
`/v1/messages` envelope. It lets you exercise the full agent loop with no API
key.

```bash
# Terminal A — start the mock LLM (listens on 127.0.0.1:4000)
python3 demo/mock-llm.py
```

```bash
# Terminal B — restart the backend with the agent enabled, pointed at the mock.
# Stop the existing container first.
docker compose stop sentinel-backend

docker compose run --rm --service-ports \
  -e AGENT_ENABLED=true \
  -e AGENT_MODE=advisory \
  -e LLM_BASE_URL=http://host.docker.internal:4000 \
  -e LLM_API_KEY=mock \
  -e LLM_MODEL=mock-claude \
  sentinel-backend
```

> On Linux: replace `host.docker.internal` with the host's docker bridge IP, or
> add `--add-host=host.docker.internal:host-gateway` to the `docker compose run`
> command.

Now confirm the agent is on:

```bash
curl -s localhost:8080/api/agent/status | python3 -m json.tool
# expect: {"enabled": true, "mode": "advisory", "model": "mock-claude", ...}
```

## 3. Ask the Copilot about the incident

```bash
# Re-seed errors so there's something to investigate.
./demo/seed-error-logs.sh payments-svc 30
sleep 35

# Ingest a runbook into the knowledge base so the agent has institutional knowledge.
./demo/ingest-runbook.sh

# Ask.
./demo/ask-agent.sh "why is payments slow right now?"
```

You should get back a JSON answer matching the FR-9 contract:

```json
{
  "summary": "Recent ERROR logs from payments-svc show a burst of JdbcSQLException...",
  "rootCause": {
    "hypothesis": "Database connection pool exhaustion on payments-svc",
    "confidence": "medium"
  },
  "evidence": [
    {"source": "logs",      "ref": "es_id=...",        "excerpt": "JdbcSQLException..."},
    {"source": "knowledge", "ref": "doc_id=...",       "excerpt": "Hikari pool exhausted..."}
  ],
  "recommendedActions": [
    {"action": "Inspect active transactions...", "rationale": "...", "requiresApproval": true}
  ],
  "trace": [ {"step": 1, "toolCalls": [...] }, {"step": 2, "toolCalls": [...]} ]
}
```

The same flow runs from the UI: open <http://localhost:3000>, click **AI
Copilot**, ask any question. You'll see step-by-step tool calls stream in, then
the final answer with the expandable evidence panel.

## 4. Inspect the safety properties

```bash
# Audit trail (NFR auditability) — every agent run lands here, even failures.
docker exec sentinel-postgres psql -U sentinel -d sentinel -c "
  SELECT id, mode, steps, input_tokens, output_tokens,
         left(answer_summary, 80) AS summary
  FROM agent_audit
  ORDER BY id DESC LIMIT 10;
"

# Confirm tool_calls + evidence are recorded.
docker exec sentinel-postgres psql -U sentinel -d sentinel -c "
  SELECT id, jsonb_pretty(tool_calls) FROM agent_audit ORDER BY id DESC LIMIT 1;
"

# Confirm the SELECT-only DB tool refuses non-SELECT (FR-5 safety gate).
# (This requires a configured db-target; without one you'll get 'Unknown target'.)
curl -s -X POST localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"please run UPDATE incidents SET status=\"RESOLVED\"","sessionId":"safety-test"}' \
  | python3 -m json.tool
# The agent loop will refuse to mutate; mock-llm only ever picks read-only tools.
```

## 5. Real LLM (when you have credentials)

Swap the mock URL + creds — no code change:

```bash
docker compose run --rm --service-ports \
  -e AGENT_ENABLED=true \
  -e LLM_BASE_URL=https://api.anthropic.com \
  -e LLM_API_KEY=$ANTHROPIC_API_KEY \
  -e LLM_MODEL=claude-opus-4-7 \
  sentinel-backend
```

For IDFC AI Hub, point at the hub URL + the sanctioned model id. The
LlmClient abstraction (FR-1) is the only seam.

## Cheat sheet

| Task                                 | Command                                    |
|--------------------------------------|--------------------------------------------|
| Spin up infra                        | `cd sentinel-backend && docker compose up -d --build` |
| Verify reachability                  | `./demo/check.sh`                          |
| Inject ERROR logs                    | `./demo/seed-error-logs.sh payments-svc 15`|
| List open incidents                  | `curl -s localhost:8080/api/dashboard/incidents/active \| jq` |
| Agent status                         | `curl -s localhost:8080/api/agent/status \| jq` |
| Ask the agent                        | `./demo/ask-agent.sh "what's happening?"`  |
| Ingest a runbook                     | `./demo/ingest-runbook.sh`                 |
| Stream answer (WebSocket)            | open Network tab on **AI Copilot** UI tab  |
| Audit trail                          | `docker exec sentinel-postgres psql -U sentinel -d sentinel -c "SELECT * FROM agent_audit LIMIT 5"` |
| Tear down                            | `docker compose down -v`                   |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `check.sh` shows `HTTP 000` for everything | docker stack isn't up yet | `docker compose ps`, wait for healthchecks |
| `/api/agent/status` returns `enabled: false` | env vars not picked up | restart backend with `AGENT_ENABLED=true` |
| No incidents after seeding logs | ES not reachable / wrong index | `curl localhost:9200/_cat/indices?v` — confirm `logs-YYYY.MM.DD` exists |
| Agent answer has empty evidence | mock LLM in use AND seed-error-logs hasn't run | run `./demo/seed-error-logs.sh` first |
| `host.docker.internal` not resolvable | Linux without docker-desktop | add `--add-host=host.docker.internal:host-gateway` or use the host's bridge IP |
| Flyway error about `vector` extension | pgvector not installed | the V6 migration swallows this — verify `agent_audit` (V7) and `knowledge_doc` (V6) both exist |

---

## Scenario B — monitor your local Spring Boot app

This uses a different compose file (`demo/docker-compose.local-app.yml`) that:
- runs Kibana / Grafana / Jaeger / Prometheus / ES / SENTINEL **in Docker**
- expects **your** Spring Boot app to run **on the host machine**
- runs **Filebeat** to ship logs written by your app to ES
- enables **Jaeger OTLP** (ports 4317/4318) so your app's OpenTelemetry agent can send traces
- configures Prometheus to scrape your app at `host.docker.internal:8080/actuator/prometheus`

### Step 1 — bring up the observability stack

```bash
cd /path/to/sentinel
mkdir -p logs

# Optional: enable the AI Copilot up-front
cat > demo/.env <<'EOF'
AGENT_ENABLED=true
LLM_BASE_URL=https://api.anthropic.com
LLM_API_KEY=sk-ant-...
LLM_MODEL=claude-opus-4-7
EOF

docker compose -f demo/docker-compose.local-app.yml up -d --build
```

Endpoints exposed on `localhost`:

| Service           | URL                                    |
|-------------------|----------------------------------------|
| SENTINEL UI       | <http://localhost:3000>                |
| SENTINEL backend  | <http://localhost:8080>                |
| Kibana            | <http://localhost:5601>                |
| Jaeger UI         | <http://localhost:16686>               |
| Grafana           | <http://localhost:3001> (admin/sentinel) |
| Prometheus        | <http://localhost:9090>                |
| Elasticsearch     | <http://localhost:9200>                |
| Jaeger OTLP HTTP  | <http://localhost:4318>                |
| Jaeger OTLP gRPC  | localhost:4317                         |

### Step 2 — instrument your Spring Boot app

Follow `demo/spring-boot-app-setup.md`. Minimum: add `micrometer-registry-prometheus`,
add the Logback JSON encoder writing to `./logs/`, run with the OpenTelemetry
Java agent. All three are config-only.

### Step 3 — start your app from the repo root

```bash
cd /path/to/sentinel       # so ./logs is the same directory Filebeat watches
mkdir -p logs

java -javaagent:./opentelemetry-javaagent.jar \
     -Dotel.service.name=my-spring-boot-app \
     -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
     -Dotel.exporter.otlp.protocol=http/protobuf \
     -Dotel.traces.exporter=otlp \
     -Dotel.metrics.exporter=none \
     -Dotel.logs.exporter=none \
     -jar /path/to/your-app.jar
```

### Step 4 — verify each signal arrives

```bash
./demo/check.sh

# Specifically:
# logs   → curl 'http://localhost:9200/logs-*/_count'           (should grow)
# metrics → open http://localhost:9090/targets                   (local-app UP)
#         OR curl 'localhost:9090/api/v1/query?query=jvm_memory_used_bytes{service="my-spring-boot-app"}'
# traces → open http://localhost:16686 → Service: my-spring-boot-app
# dashboard → open http://localhost:3000 → see your service appear in Service Health Map
```

### Step 5 — drive a synthetic incident from your app

Hit an endpoint that logs ERRORs, or just add this to your app temporarily:

```java
@GetMapping("/break-it")
public String breakIt() {
    for (int i = 0; i < 30; i++) {
        log.error("synthetic DB pool exhaustion #{}", i,
                  new RuntimeException("simulated JDBC connection timeout"));
    }
    return "OK";
}
```

```bash
curl localhost:8080/break-it      # or whatever port your app uses
sleep 35
./demo/check.sh                    # SENTINEL should now show an OPEN incident
```

### Step 6 — ask the AI Copilot about *your* service

```bash
./demo/ask-agent.sh "what's wrong with my-spring-boot-app right now?"
```

…or open the **AI Copilot** tab at <http://localhost:3000>.

### Common gotchas (Scenario B)

| Symptom | Cause | Fix |
|---|---|---|
| Prometheus `local-app` target DOWN | host port mismatch | edit `demo/prometheus.yml` and the `APM_HOST_PORT` env in `demo/docker-compose.local-app.yml`, then `docker compose -f demo/docker-compose.local-app.yml restart prometheus sentinel-backend` |
| `host.docker.internal` not resolvable | older Docker on Linux | already aliased via `extra_hosts: host-gateway` in the compose — verify with `docker exec sentinel-backend getent hosts host.docker.internal` |
| Filebeat reports `Permission denied` on `/host-logs` | host file perms too tight | `chmod -R a+r ./logs` |
| No `service.name` on log entries in ES | logback custom field not picked up | check `customFields` in the encoder config — must include `service.name` |
| Traces never appear in Jaeger | OTel exporter pointed at the wrong port | confirm `-Dotel.exporter.otlp.endpoint=http://localhost:4318` and `COLLECTOR_OTLP_ENABLED=true` in the jaeger service |
| SENTINEL doesn't tie metrics to your service | inconsistent service name across signals | the label / resource attr / customField must all be the same string (default `my-spring-boot-app`) — see the cheat sheet at the end of `spring-boot-app-setup.md` |

---

## Scenario C — run SENTINEL from VS Code / IntelliJ (infra in docker)

Best when you want to **step through SENTINEL's own code**, set breakpoints,
or make changes and see them reload immediately. Only the supporting infra
(Postgres, Redis, Kafka, ES, Kibana, Jaeger, Prometheus, Grafana, Filebeat)
runs in Docker. The sentinel-backend and sentinel-frontend run **from your IDE**.

```
┌──────────────┐  ┌────────────────────────────────┐
│  Your VS Code│  │  Docker (infra only)           │
│              │  │                                │
│  sentinel-   │─▶│  Postgres, Redis, Kafka, ES,   │
│  backend     │  │  Kibana, Jaeger, Prometheus,   │
│  (mvn s-b:run│  │  Grafana, Filebeat              │
│   or F5)     │  │                                │
│              │  └────────────────────────────────┘
│  sentinel-   │
│  frontend    │
│  (npm run dev)│
└──────────────┘
```

### One-time setup

```bash
git pull                                # make sure parent pom has the Lombok fix
mkdir -p logs                           # Filebeat watches this directory
```

### Step 1 — start the infra (Docker)

```bash
docker compose -f demo/docker-compose.infra-only.yml up -d
```

That's it for Docker. The `sentinel-backend` and `sentinel-frontend` images
do NOT build — none of those Dockerfile errors (parent pom resolution, etc.)
can happen here.

Wait ~30 seconds for Elasticsearch's healthcheck to flip green:
```bash
docker compose -f demo/docker-compose.infra-only.yml ps
# all should show "Up" or "healthy"
```

### Step 2 — run the SENTINEL backend from VS Code

**Option A — one-key debug (recommended):**
1. Open the `watch-dog/` repo folder in VS Code.
2. Copy the tracked launch + extensions config into the gitignored `.vscode/`:
   ```bash
   mkdir -p .vscode
   cp demo/vscode-config/launch.json     .vscode/launch.json
   cp demo/vscode-config/extensions.json .vscode/extensions.json
   ```
3. When prompted, install the recommended extensions (`Extension Pack for Java`,
   `Spring Boot Dashboard`, etc.).
4. Open the **Run and Debug** panel (Ctrl+Shift+D / Cmd+Shift+D).
5. Pick one of the pre-built configs from the dropdown:
   - `SENTINEL Backend (advisory mode)` — AI Copilot off
   - `SENTINEL Backend (AI Copilot ON, mock LLM)` — uses `./demo/mock-llm.py`
   - `SENTINEL Backend (AI Copilot ON, real Anthropic)` — needs `ANTHROPIC_API_KEY` env var on your shell
6. Hit F5. Breakpoints, hot reload, and the **Spring Boot Dashboard** all work.

**Option B — Maven from the terminal:**
```bash
cd sentinel-backend
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
    -DDATABASE_URL=jdbc:postgresql://localhost:5432/sentinel \
    -DREDIS_HOST=localhost \
    -DKAFKA_BROKERS=localhost:9092 \
    -DES_URL=http://localhost:9200 \
    -DJAEGER_URL=http://localhost:16686 \
    -DGRAFANA_URL=http://localhost:3001"
```

Or set the env vars in your shell first and just run `mvn spring-boot:run`.

The backend will be at <http://localhost:8080>. Hit:
```bash
curl localhost:8080/actuator/health    # should return UP
curl localhost:8080/api/dashboard/summary
```

> If you start `mock-llm.py` in another terminal first and pick the
> `AI Copilot ON, mock LLM` launch config, the agent layer comes up live.

### Step 3 — run the SENTINEL frontend from VS Code

```bash
cd sentinel-frontend
npm install     # first time only
npm run dev
```

Vite serves the UI at <http://localhost:5173>. It talks to the backend at
<http://localhost:8080> via `VITE_API_URL` (defaults correctly when running
locally — set `VITE_API_URL=http://localhost:8080` in `.env.local` if you
need to override).

Open <http://localhost:5173>:
- **Dashboard** tab — service health map, active incidents
- **AI Copilot** tab — if you ran backend with `AGENT_ENABLED=true`, the chat works here

### Step 4 — connect your Spring Boot app (sample or real)

Run your app on **port 8081** (so it doesn't clash with sentinel-backend on
8080) from the repo root:

```bash
cd /path/to/sentinel       # so ./logs is what Filebeat watches
SERVER_PORT=8081 ./path/to/your-app/run.sh
# or for the sample:
SERVER_PORT=8081 ./sentinel-sample-app/run.sh
```

Verify each signal:
```bash
# metrics — Prometheus should show local-app target UP
open http://localhost:9090/targets

# logs — Filebeat should be shipping
curl 'http://localhost:9200/logs-*/_count?q=service.name:my-spring-boot-app'

# traces — open the Jaeger UI and pick my-spring-boot-app
open http://localhost:16686
```

### Step 5 — drive an incident, then ask the agent

```bash
# From your app — fire 30 fake DB-pool errors
curl 'http://localhost:8081/break/db-pool?count=30'

# Wait ~30s for the next ES poll cycle, then verify the incident
curl http://localhost:8080/api/dashboard/incidents/active | python3 -m json.tool

# Ask the agent (works with any launch config that has AI Copilot ON)
./demo/ask-agent.sh "what's wrong with my-spring-boot-app right now?"
```

### Scenario C cheat sheet

| What | Where it runs | Command |
|---|---|---|
| Infra (Postgres, Redis, Kafka, ES, Kibana, Jaeger, Prometheus, Grafana, Filebeat) | Docker | `docker compose -f demo/docker-compose.infra-only.yml up -d` |
| sentinel-backend | **VS Code F5** OR terminal | `mvn -pl sentinel-backend spring-boot:run` |
| sentinel-frontend | terminal | `cd sentinel-frontend && npm run dev` |
| Your Spring Boot app | terminal | `SERVER_PORT=8081 ./path/to/run.sh` |
| Mock LLM (no API key) | terminal | `python3 demo/mock-llm.py` |
| Tear down infra | Docker | `docker compose -f demo/docker-compose.infra-only.yml down` |

### Why this avoids the Dockerfile build error

If you ever did hit `Non-resolvable parent POM ... sentinel-parent ...
'parent.relativePath' points at wrong local POM`, that came from the old
`sentinel-backend/Dockerfile` trying `mvn dependency:go-offline` without the
multi-module parent context copied into the build. Scenario C sidesteps it by
running Maven from your IDE / terminal with the full project tree available.

The same error is also fixed for **Scenario D** (full docker) — the Dockerfile
now expects the **repo root** as the build context and copies both `pom.xml`
(parent) and `sentinel-backend/pom.xml` (child) explicitly, with the build
contexts in `docker-compose.yml` and `demo/docker-compose.local-app.yml`
updated to match.

---

## Scenario D — full docker / pipeline path

What CI runs and what you deploy from. Every container is built and started
by `docker compose`, including `sentinel-backend` and `sentinel-frontend`.

```
┌────────────────────────────────────────────────────────────────┐
│  Docker (everything)                                            │
│                                                                 │
│  Postgres, Redis, Kafka, ES, Kibana, Jaeger, Prometheus,        │
│  Grafana                                                        │
│  + sentinel-backend (built from sentinel-backend/Dockerfile)    │
│  + sentinel-frontend (built from sentinel-frontend/Dockerfile)  │
└────────────────────────────────────────────────────────────────┘
```

### Bring it up

```bash
cd sentinel-backend
docker compose up -d --build
```

The first build takes a few minutes (Maven downloads deps into a cached
layer). Subsequent builds reuse the cache and finish in seconds.

### Verify

```bash
# Wait for the backend health endpoint to flip to UP
curl -fsS http://localhost:8080/actuator/health

# Smoke endpoints
curl -fsS http://localhost:8080/api/dashboard/summary
curl -fsS http://localhost:8080/api/agent/status

# Open the UI
open http://localhost:3000          # backend on :8080, UI on :3000
```

### Turn on the AI Copilot

In `sentinel-backend/docker-compose.yml` add the agent env vars under
`sentinel-backend` (or pass them via a `.env` file next to the compose):

```yaml
sentinel-backend:
  environment:
    AGENT_ENABLED: "true"
    LLM_BASE_URL: "https://api.anthropic.com"
    LLM_API_KEY: "${ANTHROPIC_API_KEY}"
    LLM_MODEL: "claude-opus-4-7"
```

```bash
cd sentinel-backend
ANTHROPIC_API_KEY=sk-ant-... docker compose up -d --build sentinel-backend
```

### What CI validates for you

`.github/workflows/ci.yml` runs four jobs on every push to master:

| Job | What it checks |
|---|---|
| `backend-tests` | `mvn compile` + AI-layer unit tests pass |
| `frontend-build` | `npm ci && npm run build` succeeds |
| `docker-build` | `sentinel-backend/Dockerfile` and `sentinel-frontend/Dockerfile` both build cleanly from the right contexts |
| `compose-smoke` | `docker compose up -d --build` boots, `/actuator/health` returns 200, and dashboard + agent status endpoints respond |

If `compose-smoke` goes red, the **pipeline path** is broken — surface it
before the merge.

### When to use Scenario C vs Scenario D

| You want to… | Use |
|---|---|
| Step through `AgentOrchestrator.ask(...)` with a debugger | **C** (IDE) |
| Test a code change in 5 seconds | **C** (IDE) |
| Build a release-ready image | **D** (docker) |
| Verify the boot sequence works in CI | **D** (docker) — `compose-smoke` job |
| Show someone SENTINEL in 30 seconds with no JDK installed | **D** (docker) |
| Read the code and follow the request flow | **C** (IDE) |

Both stay green in CI together — switching modes is just changing which
compose file you point at.
