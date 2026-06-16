# Run SENTINEL on your laptop with a LOCAL LLM — step by step

End-to-end walkthrough: spin up Grafana / Kibana / Jaeger / Prometheus in Docker,
run a sample Spring Boot app on the host, run a local LLM (Ollama), trigger an
error from the sample app, and watch the AI Copilot diagnose it.

**Total time: ~30 min** (most of which is the first Ollama model pull).

> **No proxy required.** SENTINEL has a native `OllamaLlmClient` that speaks
> directly to Ollama's `/api/chat` endpoint. You also get a **model dropdown
> in the UI** populated from `ollama list` — switch models at runtime, no
> rebuild, no restart.

---

## What you'll have running at the end

```
  ┌─────────────────────────────────────────────────────────────────┐
  │   YOUR LAPTOP                                                   │
  │                                                                 │
  │   ┌──────────────────┐         ┌──────────────────────────────┐ │
  │   │  Sample Spring   │  logs   │   Docker Compose stack       │ │
  │   │  Boot app        │────────▶│                              │ │
  │   │  (port 9090)     │  traces │  ┌────────────┐ ┌──────────┐ │ │
  │   │                  │────────▶│  │Elasticsearch│ │ Kibana   │ │ │
  │   │  /work    ✓ OK   │ metrics │  │ + Filebeat │ │  :5601   │ │ │
  │   │  /break   ✗ DB   │────────▶│  └────────────┘ └──────────┘ │ │
  │   │  /slow    ✗ slow │         │  ┌────────────┐ ┌──────────┐ │ │
  │   └──────────────────┘         │  │  Jaeger    │ │Prometheus│ │ │
  │                                │  │  :16686    │ │  :9090   │ │ │
  │   ┌──────────────────┐         │  └────────────┘ └──────────┘ │ │
  │   │  Ollama          │ direct  │  ┌────────────┐ ┌──────────┐ │ │
  │   │  qwen2.5-coder:  │ /api/   │  │ Grafana    │ │  Kafka   │ │ │
  │   │  14b / 7b / etc. │ chat    │  │  :3001     │ │ Postgres │ │ │
  │   │  :11434          │◀────────│  └────────────┘ │ Redis    │ │ │
  │   │                  │         │  ┌──────────────┴──────────┐ │ │
  │   └──────────────────┘         │  │  SENTINEL backend       │ │ │
  │                                │  │  :8080                  │ │ │
  │                                │  │  OllamaLlmClient        │ │ │
  │                                │  │  + AI Copilot           │ │ │
  │                                │  └─────────────────────────┘ │ │
  │                                │  ┌─────────────────────────┐ │ │
  │                                │  │  SENTINEL frontend      │ │ │
  │                                │  │  :3000  (you open this) │ │ │
  │                                │  │  ← model dropdown here  │ │ │
  │                                │  └─────────────────────────┘ │ │
  │                                └──────────────────────────────┘ │
  └─────────────────────────────────────────────────────────────────┘
```

---

## Prerequisites (10 min)

You need:
- **Docker Desktop** running (or Docker Engine + Compose v2)
- **JDK 17** or higher
- **Maven 3.9+**
- **~12 GB free RAM** (depends on model size — see size table at the end)

Check:
```bash
docker --version          # >= 20
docker compose version    # v2.x
java -version             # 17+
mvn -v                    # 3.9+
```

---

## Step 1 — Clone and verify the repo

```bash
git clone https://github.com/DileepJexpert/watch-dog.git
cd watch-dog
ls demo/                  # should list docker-compose.local-app.yml, check.sh, etc.
```

---

## Step 2 — Install and start Ollama (the local LLM)

### macOS / Windows
Download from <https://ollama.com/download> and install. Ollama starts automatically.

### Linux
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

### Pull one or more models

Pull whichever you want — the UI will list every model you have. Best
recommendations for tool/function-calling with SENTINEL's agent:

```bash
# Best for tool-use at 14B — needs ~10 GB RAM, ~10-20s per step
ollama pull qwen2.5-coder:14b

# Lighter alternative — ~7 GB RAM, ~5-10s per step
ollama pull qwen2.5-coder:7b

# (Optional) keep the small one around for quick smoke tests
ollama pull llama3.2:3b-instruct-q4_0
```

Verify:
```bash
ollama list
# NAME                         ID              SIZE      MODIFIED
# qwen2.5-coder:14b            xxxxxxxxxxxx    9.0 GB    just now
# qwen2.5-coder:7b             xxxxxxxxxxxx    4.7 GB    just now
# llama3.2:3b-instruct-q4_0    xxxxxxxxxxxx    1.9 GB    just now
```

### Smoke-test Ollama

```bash
curl http://localhost:11434/api/chat -d '{
  "model": "qwen2.5-coder:14b",
  "messages": [{"role": "user", "content": "Say hello in one short sentence."}],
  "stream": false
}'
# Should return JSON with message.content set. If this works, the model is live.
```

---

## Step 3 — Start the SENTINEL + observability stack (Docker)

> **No proxy needed.** SENTINEL talks to Ollama directly via
> `OllamaLlmClient` (selected by `LLM_PROVIDER=ollama`). You can also pick
> the active model from a dropdown in the UI — no env-var edits needed
> to switch.

```bash
cd /path/to/sentinel
mkdir -p logs                     # the sample app will write JSON logs here
```

### Configure the AI Copilot to use native Ollama

```bash
cat > demo/.env <<'EOF'
AGENT_ENABLED=true
AGENT_MODE=advisory
LLM_PROVIDER=ollama
LLM_BASE_URL=http://host.docker.internal:11434
LLM_API_KEY=not-needed
LLM_MODEL=qwen2.5-coder:14b
LLM_TIMEOUT_MS=180000
APM_HOST_PORT=9090
EOF
```

**Windows PowerShell** (equivalent):
```powershell
@"
AGENT_ENABLED=true
AGENT_MODE=advisory
LLM_PROVIDER=ollama
LLM_BASE_URL=http://host.docker.internal:11434
LLM_API_KEY=not-needed
LLM_MODEL=qwen2.5-coder:14b
LLM_TIMEOUT_MS=180000
APM_HOST_PORT=9090
"@ | Out-File -FilePath demo\.env -Encoding ascii
```

> `host.docker.internal` lets the SENTINEL container reach Ollama running
> on your host machine (port 11434). `APM_HOST_PORT=9090` tells SENTINEL
> the sample app listens on port 9090 (avoids clashing with the backend on 8080).
> `LLM_MODEL` is just the initial pick — you can change it from the UI dropdown later.

### Bring up the stack

```bash
docker compose -f demo/docker-compose.local-app.yml --env-file demo/.env up -d --build
```

First run will pull ~5 GB of images. Subsequent runs are ~30 seconds.

### Verify everything is up

```bash
docker compose -f demo/docker-compose.local-app.yml ps
# All services should be "running" or "healthy"

./demo/check.sh
# Should show HTTP 200 for backend, ES, Kibana, Jaeger, Grafana, Prometheus
```

Open in your browser:

| What | URL | Login |
|------|-----|-------|
| SENTINEL UI | <http://localhost:3000> | none |
| Kibana (logs) | <http://localhost:5601> | none |
| Jaeger (traces) | <http://localhost:16686> | none |
| Grafana (metrics) | <http://localhost:3001> | admin / sentinel |
| Prometheus | <http://localhost:9090> | none |

The **AI Copilot** tab on the SENTINEL UI should now say `enabled: true`,
`provider: ollama`, and `model: qwen2.5-coder:14b`. There will be a **Model**
dropdown in the header listing every model from `ollama list` — pick any
of them and the next agent call uses it.

Confirm via API:
```bash
curl -s localhost:8080/api/agent/status | python3 -m json.tool
# {
#   "enabled": true,
#   "mode": "advisory",
#   "provider": "ollama",
#   "model": "qwen2.5-coder:14b",
#   "modelSwitchSupported": true,
#   ...
# }

# List the models the dropdown will show
curl -s localhost:8080/api/agent/models | python3 -m json.tool
# {
#   "provider": "ollama",
#   "active": "qwen2.5-coder:14b",
#   "models": [
#     {"name": "qwen2.5-coder:14b",            "parameterSize": "14B", "size": 9000000000, ...},
#     {"name": "qwen2.5-coder:7b",             "parameterSize": "7B",  "size": 4700000000, ...},
#     {"name": "deepseek-coder:6.7b",          "parameterSize": "7B",  "size": 3800000000, ...},
#     {"name": "llama3.2:3b-instruct-q4_0",    "parameterSize": "3B",  "size": 1900000000, ...}
#   ]
# }

# Switch the active model at runtime (no restart needed)
curl -X POST localhost:8080/api/agent/model \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-coder:7b"}'
# {"active":"qwen2.5-coder:7b","message":"Active model updated"}
```

---

## Step 4 — Create a minimal sample Spring Boot app

We'll build a tiny app with three endpoints: `/work` (healthy), `/break` (logs errors), `/slow` (slow trace).

### Generate the project

```bash
cd /tmp
curl -s https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.3.0 \
  -d baseDir=demo-app \
  -d groupId=com.demo \
  -d artifactId=demo-app \
  -d name=demo-app \
  -d packageName=com.demo \
  -d javaVersion=17 \
  -d dependencies=web,actuator \
  -o demo-app.zip
unzip -q demo-app.zip && cd demo-app
```

### Add observability dependencies

Open `pom.xml` and add these inside `<dependencies>`:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```

### Configure the app

Replace `src/main/resources/application.properties` with `application.yml`:

```bash
rm src/main/resources/application.properties
cat > src/main/resources/application.yml <<'EOF'
server:
  port: 9090
spring:
  application:
    name: demo-app
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health,info,metrics
  metrics:
    tags:
      service: demo-app
      application: demo-app
EOF
```

### Add JSON file logging

```bash
cat > src/main/resources/logback-spring.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property name="APP_NAME" value="demo-app"/>
  <property name="LOG_DIR"  value="${LOG_DIR:-./logs}"/>

  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_DIR}/${APP_NAME}.json</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>${LOG_DIR}/${APP_NAME}.%d{yyyy-MM-dd}.%i.json</fileNamePattern>
      <maxFileSize>50MB</maxFileSize>
      <maxHistory>3</maxHistory>
    </rollingPolicy>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <customFields>{"service.name":"demo-app"}</customFields>
      <provider class="net.logstash.logback.composite.loggingevent.LogLevelJsonProvider">
        <fieldName>log.level</fieldName>
      </provider>
      <provider class="net.logstash.logback.composite.loggingevent.StackTraceJsonProvider">
        <fieldName>error.stack_trace</fieldName>
      </provider>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="JSON_FILE"/>
  </root>
</configuration>
EOF
```

### Add the demo controller

```bash
cat > src/main/java/com/demo/DemoController.java <<'EOF'
package com.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {
  private static final Logger log = LoggerFactory.getLogger(DemoController.class);

  @GetMapping("/")
  public String home() { return "demo-app up\n"; }

  @GetMapping("/work")
  public String work() {
    log.info("processing healthy request");
    return "OK\n";
  }

  @GetMapping("/break")
  public String breakIt() {
    for (int i = 0; i < 30; i++) {
      log.error("synthetic DB pool exhaustion #{}: HikariPool-1 - Connection is not available, request timed out after 30000ms", i,
        new RuntimeException("simulated JdbcSQLException: timeout acquiring connection"));
    }
    return "broke 30 times\n";
  }

  @GetMapping("/slow")
  public String slow() throws InterruptedException {
    log.warn("slow request — simulated 3s downstream call");
    Thread.sleep(3000);
    log.error("downstream timeout on payments-api after 3000ms");
    return "slow done\n";
  }
}
EOF
```

### Download the OpenTelemetry Java agent (for traces)

```bash
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
```

### Build the app

```bash
mvn -q -DskipTests package
ls target/demo-app-*.jar
```

---

## Step 5 — Run the sample app from the watch-dog repo root

> Important: run from the **watch-dog repo root** so the `./logs/` directory
> matches what Filebeat (in Docker) is watching.

Open a **new terminal**:

```bash
cd /path/to/sentinel
mkdir -p logs

java \
  -javaagent:/tmp/demo-app/opentelemetry-javaagent.jar \
  -Dotel.service.name=demo-app \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -jar /tmp/demo-app/target/demo-app-0.0.1-SNAPSHOT.jar
```

You should see Spring Boot start on **port 9090**.

### Verify each signal flows

```bash
# 1. App is responding
curl http://localhost:9090/work

# 2. Metrics endpoint is exposed
curl -s http://localhost:9090/actuator/prometheus | grep jvm_memory_used_bytes | head -2

# 3. Prometheus is scraping the app
open http://localhost:9090/targets   # the `local-app` target should be UP
# OR
curl -s 'http://localhost:9090/api/v1/query?query=up{job="local-app"}'

# 4. Logs are landing in Elasticsearch (Filebeat ships them within ~5s)
ls logs/
curl -s 'http://localhost:9200/logs-*/_count' | python3 -m json.tool

# 5. Traces are landing in Jaeger
open http://localhost:16686    # Service dropdown should show "demo-app"
```

If any of these fail, check `./demo/README.md` → Troubleshooting section.

---

## Step 6 — Trigger an error from the sample app

```bash
# Fires 30 ERROR logs with stack traces in one shot
curl http://localhost:9090/break

# Wait ~35s for the SENTINEL ingestion poll + correlation tick
sleep 35
```

### What should happen

1. Filebeat ships the 30 ERROR logs to Elasticsearch (~5s)
2. SENTINEL's `ElasticsearchConnector` polls ES every 30s and finds them
3. Events get normalized → Kafka → `CorrelationEngine`
4. One of the 20 rules fires — likely `HighErrorRateRule` or
   `ConnectionPoolExhaustionRule` (based on the message content)
5. An `IncidentEntity` is created in Postgres
6. The dashboard's "Active Incidents" panel updates via WebSocket

### Verify the incident was created

```bash
curl -s localhost:8080/api/dashboard/incidents/active | python3 -m json.tool
```

You should see a JSON array with one item — `serviceName: "demo-app"`, status `OPEN`.

Open <http://localhost:3000> — you'll see the incident on the dashboard.

---

## Step 7 — Ask the AI Copilot (using your LOCAL Ollama model)

### Option A — From the UI

1. Open <http://localhost:3000>
2. Click the **AI Copilot** tab
3. Ask: `why is demo-app throwing errors right now?`
4. Watch tool calls stream in the right-hand panel as the agent investigates:
   - `search_logs(service=demo-app, level=ERROR)` → returns the JDBC exception messages
   - `query_metrics(service=demo-app)` → returns recent metric series
   - `correlate(service=demo-app)` → returns the open incident
   - `finalize_answer(...)` → renders the FR-9 structured answer

### Option B — From the command line

```bash
curl -s -X POST localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"why is demo-app throwing errors right now?","sessionId":"local-test"}' \
  | python3 -m json.tool
```

You should get back a JSON answer like:

```json
{
  "summary": "demo-app is experiencing repeated database connection failures...",
  "rootCause": {
    "hypothesis": "HikariCP connection pool exhausted — pool size insufficient or connections leaked",
    "confidence": "medium",
    "service": "demo-app"
  },
  "evidence": [
    {
      "source": "logs",
      "ref": "es_id=...",
      "excerpt": "HikariPool-1 - Connection is not available, request timed out after 30000ms"
    },
    {
      "source": "correlate",
      "ref": "incident_id=...",
      "excerpt": "Rule: HighErrorRate matched (30 errors in 1 minute)"
    }
  ],
  "recommendedActions": [
    {
      "action": "Inspect long-running transactions holding pool connections",
      "rationale": "...",
      "requiresApproval": true
    }
  ],
  "trace": [
    {"step": 1, "toolCalls": [{"tool": "search_logs", "args": {...}}]},
    {"step": 2, "toolCalls": [{"tool": "correlate", "args": {...}}]}
  ]
}
```

### What's happening under the hood

```
You ask question
      │
      ▼
SENTINEL backend (Docker)
OllamaLlmClient
      │
      │ HTTP POST /api/chat (native Ollama format)
      ▼
Ollama on host:11434
      │
      │ qwen2.5-coder:14b decides which tools to call
      ▼
Back to SENTINEL → executes search_logs(), correlate(), etc. in parallel
      │
      │ tool outputs sent back as role:tool messages
      ▼
Ollama → final answer (calls finalize_answer tool)
      │
      ▼
Returned as FR-9 AgentAnswer
```

---

## Step 8 — Inspect the audit trail (proof it worked)

Every agent run is recorded in `agent_audit`:

```bash
docker exec sentinel-postgres psql -U sentinel -d sentinel -c "
  SELECT id, mode, steps, input_tokens, output_tokens,
         left(answer_summary, 80) AS summary
  FROM agent_audit
  ORDER BY id DESC LIMIT 5;
"
```

You'll see your run with the step count, token usage, and a snippet of the answer.

To see the full tool-call breakdown:

```bash
docker exec sentinel-postgres psql -U sentinel -d sentinel -c "
  SELECT jsonb_pretty(tool_calls) FROM agent_audit ORDER BY id DESC LIMIT 1;
"
```

---

## Cheat sheet — daily workflow once everything's set up

| Task | Command |
|------|---------|
| Start the Docker stack | `docker compose -f demo/docker-compose.local-app.yml --env-file demo/.env up -d` |
| Start the sample app | (from repo root) `java -javaagent:.../opentelemetry-javaagent.jar -D... -jar /tmp/demo-app/target/demo-app-0.0.1-SNAPSHOT.jar` |
| Trigger errors | `curl http://localhost:9090/break` |
| Ask the agent | `curl -X POST localhost:8080/api/agent/ask -H 'Content-Type: application/json' -d '{"question":"...","sessionId":"x"}'` |
| List Ollama models | `curl localhost:8080/api/agent/models \| jq` |
| Switch active model | `curl -X POST localhost:8080/api/agent/model -H 'Content-Type: application/json' -d '{"model":"qwen2.5-coder:7b"}'` (or just pick from the UI dropdown) |
| Verify reachability | `./demo/check.sh` |
| Open dashboard | <http://localhost:3000> |
| Tear down Docker | `docker compose -f demo/docker-compose.local-app.yml down -v` |
| Stop sample app | `Ctrl+C` in its terminal |
| Stop Ollama | `pkill ollama` (macOS/Linux) |

---

## Switching models from the UI (the easy way)

1. Open <http://localhost:3000> → **AI Copilot** tab
2. Top-right of the chat: **Model** dropdown lists every model from `ollama list`
3. Pick a different model → next agent call uses it (no restart, no env edit)
4. Bottom-left status updates to confirm the change

If you pull a new model later (`ollama pull qwen2.5:32b`), the dropdown
refreshes on page reload.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `/api/agent/status` returns `enabled: false` | env vars not picked up | `docker compose -f demo/docker-compose.local-app.yml --env-file demo/.env up -d sentinel-backend` |
| `provider: anthropic` in status | forgot `LLM_PROVIDER=ollama` | Add to `demo/.env`, then `docker compose ... up -d sentinel-backend` |
| Model dropdown empty | Ollama unreachable from container | Verify with `docker exec sentinel-backend curl -sf http://host.docker.internal:11434/api/tags`. On Linux this requires `extra_hosts: host-gateway` (already set in the compose file) |
| Agent answer takes > 2 minutes | local model is slow on first call | First call warms up the model — subsequent calls are faster. Bump `LLM_TIMEOUT_MS=300000` for very large models |
| Prometheus `local-app` target DOWN | sample app on wrong port | Confirm `server.port: 9090` in the app and `APM_HOST_PORT=9090` in `demo/.env` |
| No incidents after `/break` | logs not reaching ES | `curl localhost:9200/_cat/indices?v` — confirm `logs-YYYY.MM.DD` exists |
| Agent loops without finalizing | smaller model can't follow tool format | Switch to a 14B+ model from the dropdown, OR lower `AGENT_MAX_STEPS=4` |
| Agent returns generic answer with no evidence | model didn't emit tool calls — likely a non tool-capable model | Pick a model that supports tools: qwen2.5-coder family is good, llama3.2:3b is borderline |
| OOM on Ollama | model too big for RAM | Switch to a smaller model from the dropdown (e.g. `qwen2.5-coder:7b`) |

---

## Model size vs quality cheat sheet

(These are the models you have today.)

| Model | Size | RAM | Quality for tool-use | Speed (per agent step) |
|-------|------|-----|----------------------|------------------------|
| `qwen2.5-coder:14b` | 9.0 GB | ~10 GB | **Best** — tool-use trained | ~10-20s |
| `qwen2.5-coder:7b` | 4.7 GB | ~7 GB | Very good | ~5-10s |
| `deepseek-coder:6.7b` | 3.8 GB | ~6 GB | OK | ~5-8s |
| `llama3.2:3b-instruct-q4_0` | 1.9 GB | ~3 GB | Weak (often hallucinates tool args) | ~2-5s |

**Recommendation**: start with `qwen2.5-coder:14b` for best results; switch to
`qwen2.5-coder:7b` from the UI dropdown if it's too slow.

That's it — no rebuild, no proxy, no code change. The provider abstraction
(FR-1) gives you native Ollama support via `OllamaLlmClient` and the model
dropdown manages everything at runtime.
