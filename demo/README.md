# WATCHDOG local demo

End-to-end walkthrough for verifying — on your laptop, with Docker — that:

1. WATCHDOG is up and the infra it depends on (Postgres, Redis, Kafka, Elasticsearch, Jaeger, Prometheus, Grafana) is reachable
2. WATCHDOG ingests ERROR logs from Elasticsearch and fires correlation rules
3. The AI Copilot can answer questions about a live incident using its tools
4. The FR-9 evidence-cited answer renders correctly in the dashboard

The scripts live in this folder. They use only `curl` + `python3` — no extra installs.

## 0. One-time setup

```bash
cd /path/to/watch-dog

# Pull + start everything (postgres, redis, kafka, ES, kibana, jaeger,
# prometheus, grafana, watchdog backend, watchdog frontend).
docker compose up -d --build

# Confirm reachability (HTTP codes should all be 200, or 404 for grafana root).
./demo/check.sh
```

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
- `Recent ERROR logs visible to WATCHDOG` shows 15 entries under `payments-svc`
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
docker compose stop watchdog-backend

docker compose run --rm --service-ports \
  -e AGENT_ENABLED=true \
  -e AGENT_MODE=advisory \
  -e LLM_BASE_URL=http://host.docker.internal:4000 \
  -e LLM_API_KEY=mock \
  -e LLM_MODEL=mock-claude \
  watchdog-backend
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
docker exec watchdog-postgres psql -U watchdog -d watchdog -c "
  SELECT id, mode, steps, input_tokens, output_tokens,
         left(answer_summary, 80) AS summary
  FROM agent_audit
  ORDER BY id DESC LIMIT 10;
"

# Confirm tool_calls + evidence are recorded.
docker exec watchdog-postgres psql -U watchdog -d watchdog -c "
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
  watchdog-backend
```

For IDFC AI Hub, point at the hub URL + the sanctioned model id. The
LlmClient abstraction (FR-1) is the only seam.

## Cheat sheet

| Task                                 | Command                                    |
|--------------------------------------|--------------------------------------------|
| Spin up infra                        | `docker compose up -d --build`             |
| Verify reachability                  | `./demo/check.sh`                          |
| Inject ERROR logs                    | `./demo/seed-error-logs.sh payments-svc 15`|
| List open incidents                  | `curl -s localhost:8080/api/dashboard/incidents/active \| jq` |
| Agent status                         | `curl -s localhost:8080/api/agent/status \| jq` |
| Ask the agent                        | `./demo/ask-agent.sh "what's happening?"`  |
| Ingest a runbook                     | `./demo/ingest-runbook.sh`                 |
| Stream answer (WebSocket)            | open Network tab on **AI Copilot** UI tab  |
| Audit trail                          | `docker exec watchdog-postgres psql -U watchdog -d watchdog -c "SELECT * FROM agent_audit LIMIT 5"` |
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
