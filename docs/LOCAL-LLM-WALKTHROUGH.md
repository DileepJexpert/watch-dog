# Run WATCHDOG on your laptop with a LOCAL LLM — step by step

End-to-end walkthrough: spin up Grafana / Kibana / Jaeger / Prometheus in Docker,
run a sample Spring Boot app on the host, run a local LLM (Ollama), trigger an
error from the sample app, and watch the AI Copilot diagnose it.

**Total time: ~30 min** (most of which is the first Ollama model pull).

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
  │   │  Ollama          │ LLM     │  ┌────────────┐ ┌──────────┐ │ │
  │   │  (llama3.1 8B)   │ calls   │  │ Grafana    │ │  Kafka   │ │ │
  │   │  :11434          │◀────────│  │  :3001     │ │ Postgres │ │ │
  │   │                  │         │  └────────────┘ │ Redis    │ │ │
  │   └──────────────────┘         │  ┌──────────────┴──────────┐ │ │
  │                                │  │  WATCHDOG backend       │ │ │
  │   ┌──────────────────┐         │  │  :8080                  │ │ │
  │   │  LiteLLM proxy   │◀────────│  │  + AI Copilot           │ │ │
  │   │  :4000           │         │  └─────────────────────────┘ │ │
  │   │  (Anthropic →    │         │  ┌─────────────────────────┐ │ │
  │   │   Ollama bridge) │         │  │  WATCHDOG frontend      │ │ │
  │   └──────────────────┘         │  │  :3000  (you open this) │ │ │
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
- **Python 3.10+** (for the LiteLLM proxy)
- **~12 GB free RAM** (Ollama 8B model + Docker stack)

Check:
```bash
docker --version          # >= 20
docker compose version    # v2.x
java -version             # 17+
mvn -v                    # 3.9+
python3 --version         # 3.10+
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

### Pull a model

```bash
# 8B model — needs ~8 GB RAM, fast enough for tool-use loops
ollama pull llama3.1

# Verify the model is loaded
ollama list
# NAME              ID            SIZE      MODIFIED
# llama3.1:latest   xxxxxxxxxxxx  4.7 GB    just now
```

### Smoke-test Ollama

```bash
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.1",
  "prompt": "Say hello in one short sentence.",
  "stream": false
}'
# Should return JSON with a "response" field. If this works, the model is live.
```

---

## Step 3 — Start the LiteLLM proxy (translates Anthropic API → Ollama)

WATCHDOG's `AnthropicLlmClient` speaks the Anthropic Messages API (`/v1/messages`).
LiteLLM is a tiny proxy that accepts that format and forwards to Ollama.

### Install

```bash
# Use a virtualenv to avoid polluting system Python
python3 -m venv ~/.litellm-venv
source ~/.litellm-venv/bin/activate
pip install 'litellm[proxy]'
```

### Start the proxy

Open a **dedicated terminal** (it needs to stay running):

```bash
source ~/.litellm-venv/bin/activate
litellm --model ollama/llama3.1 --port 4000 --drop_params
```

You'll see:
```
LiteLLM: Proxy initialized with model: ollama/llama3.1
Uvicorn running on http://0.0.0.0:4000
```

### Smoke-test the proxy

In a different terminal:
```bash
curl -s http://localhost:4000/v1/messages \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: not-needed' \
  -H 'anthropic-version: 2023-06-01' \
  -d '{
    "model": "ollama/llama3.1",
    "max_tokens": 100,
    "messages": [{"role": "user", "content": "Say hello in one short sentence."}]
  }' | python3 -m json.tool
```

You should see a response in **Anthropic Messages format** (with `content: [{type: "text", text: "..."}]`). If yes — the LLM endpoint is ready.

---

## Step 4 — Start the WATCHDOG + observability stack (Docker)

```bash
cd /path/to/watch-dog
mkdir -p logs                     # the sample app will write JSON logs here
```

### Configure the AI Copilot with local LLM env vars

```bash
cat > demo/.env <<'EOF'
AGENT_ENABLED=true
AGENT_MODE=advisory
LLM_BASE_URL=http://host.docker.internal:4000
LLM_API_KEY=not-needed
LLM_MODEL=ollama/llama3.1
APM_HOST_PORT=9090
EOF
```

> `host.docker.internal` lets containers reach LiteLLM running on your host.
> `APM_HOST_PORT=9090` tells WATCHDOG the sample app listens on port 9090
> (we use 9090 to avoid clashing with the backend on 8080).

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
| WATCHDOG UI | <http://localhost:3000> | none |
| Kibana (logs) | <http://localhost:5601> | none |
| Jaeger (traces) | <http://localhost:16686> | none |
| Grafana (metrics) | <http://localhost:3001> | admin / watchdog |
| Prometheus | <http://localhost:9090> | none |

The **AI Copilot** tab on the WATCHDOG UI should now say `enabled: true` and `model: ollama/llama3.1`.

Confirm via API:
```bash
curl -s localhost:8080/api/agent/status | python3 -m json.tool
# {
#   "enabled": true,
#   "mode": "advisory",
#   "model": "ollama/llama3.1",
#   ...
# }
```

---

## Step 5 — Create a minimal sample Spring Boot app

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

## Step 6 — Run the sample app from the watch-dog repo root

> Important: run from the **watch-dog repo root** so the `./logs/` directory
> matches what Filebeat (in Docker) is watching.

Open a **new terminal**:

```bash
cd /path/to/watch-dog
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

## Step 7 — Trigger an error from the sample app

```bash
# Fires 30 ERROR logs with stack traces in one shot
curl http://localhost:9090/break

# Wait ~35s for the WATCHDOG ingestion poll + correlation tick
sleep 35
```

### What should happen

1. Filebeat ships the 30 ERROR logs to Elasticsearch (~5s)
2. WATCHDOG's `ElasticsearchConnector` polls ES every 30s and finds them
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

## Step 8 — Ask the AI Copilot (using your LOCAL Ollama model)

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
WATCHDOG backend (Docker)
      │
      │ HTTP POST /v1/messages (Anthropic format)
      ▼
LiteLLM proxy on host:4000
      │
      │ translates → Ollama format
      ▼
Ollama on host:11434
      │
      │ llama3.1 8B decides which tools to call
      ▼
Back to WATCHDOG → executes search_logs(), correlate(), etc.
      │
      │ tool outputs sent back as tool_result blocks
      ▼
LiteLLM → Ollama → final answer
      │
      ▼
Returned as FR-9 AgentAnswer
```

---

## Step 9 — Inspect the audit trail (proof it worked)

Every agent run is recorded in `agent_audit`:

```bash
docker exec watchdog-postgres psql -U watchdog -d watchdog -c "
  SELECT id, mode, steps, input_tokens, output_tokens,
         left(answer_summary, 80) AS summary
  FROM agent_audit
  ORDER BY id DESC LIMIT 5;
"
```

You'll see your run with the step count, token usage, and a snippet of the answer.

To see the full tool-call breakdown:

```bash
docker exec watchdog-postgres psql -U watchdog -d watchdog -c "
  SELECT jsonb_pretty(tool_calls) FROM agent_audit ORDER BY id DESC LIMIT 1;
"
```

---

## Cheat sheet — daily workflow once everything's set up

| Task | Command |
|------|---------|
| Start LiteLLM proxy | `source ~/.litellm-venv/bin/activate && litellm --model ollama/llama3.1 --port 4000 --drop_params` |
| Start the Docker stack | `docker compose -f demo/docker-compose.local-app.yml --env-file demo/.env up -d` |
| Start the sample app | (from repo root) `java -javaagent:.../opentelemetry-javaagent.jar -D... -jar /tmp/demo-app/target/demo-app-0.0.1-SNAPSHOT.jar` |
| Trigger errors | `curl http://localhost:9090/break` |
| Ask the agent | `curl -X POST localhost:8080/api/agent/ask -H 'Content-Type: application/json' -d '{"question":"...","sessionId":"x"}'` |
| Verify reachability | `./demo/check.sh` |
| Open dashboard | <http://localhost:3000> |
| Tear down Docker | `docker compose -f demo/docker-compose.local-app.yml down -v` |
| Stop sample app | `Ctrl+C` in its terminal |
| Stop LiteLLM | `Ctrl+C` in its terminal |
| Stop Ollama | `pkill ollama` (macOS/Linux) |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `/api/agent/status` returns `enabled: false` | env vars not picked up | `docker compose -f demo/docker-compose.local-app.yml --env-file demo/.env up -d watchdog-backend` |
| Agent answer takes > 2 minutes | local model is slow on first call | Set `LLM_TIMEOUT_MS=180000` in `demo/.env` and restart backend |
| `host.docker.internal` not resolvable (Linux) | older Docker | The compose file already aliases via `extra_hosts: host-gateway` — verify with `docker exec watchdog-backend getent hosts host.docker.internal` |
| Prometheus `local-app` target DOWN | sample app on wrong port | Confirm `server.port: 9090` in the app and `APM_HOST_PORT=9090` in `demo/.env` |
| No incidents after `/break` | logs not reaching ES | `curl localhost:9200/_cat/indices?v` — confirm `logs-YYYY.MM.DD` exists and has docs |
| LiteLLM proxy: `model not found` | Ollama not running or model not pulled | `ollama list` — should show `llama3.1` |
| Agent loops without finalizing | small model can't follow tool format | Try `ollama pull llama3.1:70b` if you have RAM, OR lower `AGENT_MAX_STEPS=4` in `demo/.env` |
| Agent returns generic answer with no evidence | model couldn't parse tools — `--drop_params` not set | Restart LiteLLM with `--drop_params` flag |
| OOM on Ollama | model too big for RAM | Use a smaller model: `ollama pull mistral` (7B), then `LLM_MODEL=ollama/mistral` |

---

## Model size vs quality cheat sheet

| Model | Size | RAM | Quality for tool-use | Speed (per agent step) |
|-------|------|-----|----------------------|------------------------|
| `llama3.1` (8B) | 4.7 GB | ~8 GB | Good | ~5-10s |
| `mistral` (7B) | 4.1 GB | ~6 GB | OK | ~4-8s |
| `qwen2.5:7b` | 4.4 GB | ~7 GB | Very good for tools | ~5-10s |
| `llama3.1:70b` | 40 GB | ~48 GB | Excellent | ~20-40s |
| `qwen2.5:32b` | 19 GB | ~24 GB | Excellent for tools | ~15-25s |

For first testing, start with `llama3.1` (8B). If answers feel weak, try `qwen2.5:7b` — it's tuned for tool/function-calling.

To switch:
```bash
ollama pull qwen2.5:7b
# Restart LiteLLM:
litellm --model ollama/qwen2.5:7b --port 4000 --drop_params
# Update demo/.env:
sed -i 's|LLM_MODEL=.*|LLM_MODEL=ollama/qwen2.5:7b|' demo/.env
docker compose -f demo/docker-compose.local-app.yml --env-file demo/.env up -d watchdog-backend
```

That's the entire switch — no rebuild, no code change.
