# Wire your local Spring Boot app to the SENTINEL stack

These are the minimum changes your app needs so SENTINEL can see its **logs**,
**traces**, and **metrics**. All of them are config-only — no code changes.

> Assumes the docker stack from `demo/docker-compose.local-app.yml` is up. From
> the repo root run:
> ```bash
> mkdir -p logs
> docker compose -f demo/docker-compose.local-app.yml up -d --build
> ```

## 1. Metrics — Prometheus / Micrometer

Add the Prometheus registry dependency.

**Maven** (`pom.xml`):
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
```

**Gradle**:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
runtimeOnly  'io.micrometer:micrometer-registry-prometheus'
```

Expose the endpoints (`application.yml`):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health,info,metrics
  metrics:
    tags:
      # IMPORTANT: SENTINEL's correlation rules key off this label. Use the
      # same value you put under `service:` in demo/prometheus.yml.
      service: my-spring-boot-app
      application: my-spring-boot-app
```

Verify locally:
```bash
curl -s localhost:8080/actuator/prometheus | head
# expect lines like:
#   jvm_memory_used_bytes{...,service="my-spring-boot-app",...} ...
```

Prometheus (running in docker) scrapes `host.docker.internal:8080/actuator/prometheus`
every 15s. You can watch it pick up your targets at <http://localhost:9090/targets>
— `local-app` should show UP.

## 2. Logs — Logback JSON → file → Filebeat → Elasticsearch

The compose file runs Filebeat with `./logs:/host-logs:ro` mounted, so anything
your app writes to `./logs/*.json` is shipped to Elasticsearch under
`logs-YYYY.MM.DD` (the index pattern SENTINEL polls).

Add the Logstash Logback encoder.

**Maven**:
```xml
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```

Create `src/main/resources/logback-spring.xml` (or merge into your existing one):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property name="APP_NAME" value="my-spring-boot-app"/>
  <property name="LOG_DIR"  value="${LOG_DIR:-./logs}"/>

  <!-- Console: keep your existing pretty output for local dev -->
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- JSON file: one document per line, ECS-aligned for ES + SENTINEL -->
  <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_DIR}/${APP_NAME}.json</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>${LOG_DIR}/${APP_NAME}.%d{yyyy-MM-dd}.%i.json</fileNamePattern>
      <maxFileSize>50MB</maxFileSize>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <fieldNames>
        <timestamp>@timestamp</timestamp>
        <message>message</message>
        <thread>[ignore]</thread>
        <logger>logger.name</logger>
        <levelValue>[ignore]</levelValue>
      </fieldNames>
      <customFields>{"service.name":"${APP_NAME}","log.level":"INFO"}</customFields>
      <!-- Promote `level` to `log.level` so SENTINEL's ES query (which filters
           on log.level) actually matches. -->
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
```

Run your app from the **repo root** (so `./logs/` is the same directory the
compose file mounts):
```bash
cd /path/to/sentinel
mkdir -p logs
java -jar /path/to/your-app/target/your-app.jar
# OR
mvn -f /path/to/your-app/pom.xml spring-boot:run
```

Verify:
```bash
# logs land in the host directory
ls -la logs/

# Filebeat ships them — should appear in ES within a few seconds
curl -s 'http://localhost:9200/logs-*/_count' | python3 -m json.tool
```

## 3. Traces — OpenTelemetry → Jaeger OTLP

Easiest path: the OpenTelemetry Java agent. No code changes, just a JVM flag.

```bash
# One-time download (any version >= 1.32)
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Start your app with the agent attached
java \
  -javaagent:./opentelemetry-javaagent.jar \
  -Dotel.service.name=my-spring-boot-app \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.traces.exporter=otlp \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -jar your-app.jar
```

Verify in Jaeger UI: <http://localhost:16686> — pick `my-spring-boot-app` from
the **Service** dropdown.

SENTINEL polls Jaeger every 60s and surfaces slow / error traces into the same
correlation engine that processes the logs and metrics.

## 4. Tell SENTINEL about your service name

The label / service name must match across the three signals:

| Signal  | Where to set it                                   | Field                |
|---------|---------------------------------------------------|----------------------|
| Logs    | `customFields` in logback-spring.xml              | `service.name`       |
| Metrics | `management.metrics.tags.service` in application.yml | `service` label   |
| Traces  | `-Dotel.service.name=...` JVM flag                | OTel resource attr   |

Keep them identical (e.g. `my-spring-boot-app`) — SENTINEL's sliding-window
correlation engine groups events by service name. Mismatched names = no
cross-source correlation.

## 5. End-to-end smoke test

```bash
# from repo root
./demo/check.sh
```

You should see:
- SENTINEL actuator HTTP 200
- ES + Kibana + Jaeger + Grafana reachable
- Your app's ERROR logs counted in the last 5 minutes (if any have fired)
- `agent` status enabled/disabled per your config

Trigger a synthetic incident in your app — e.g. log a bunch of ERRORs in a
loop:
```java
for (int i = 0; i < 30; i++) {
    log.error("synthetic test error #{}: simulated DB connection issue", i,
              new RuntimeException("synthetic DB pool exhausted"));
}
```

Within ~30s SENTINEL's correlation engine will create an incident — visible
at <http://localhost:3000>.

## 6. Turn on the AI Copilot against this stack

```bash
# In demo/docker-compose.local-app.yml, the backend already reads these from env.
# Easiest: create demo/.env (or export) before bringing up the stack:
cat > demo/.env <<'EOF'
AGENT_ENABLED=true
AGENT_MODE=advisory
LLM_BASE_URL=https://api.anthropic.com
LLM_API_KEY=sk-ant-...
LLM_MODEL=claude-opus-4-7
EOF

docker compose -f demo/docker-compose.local-app.yml up -d --build sentinel-backend

# Or use the mock LLM (no creds needed) — see demo/README.md section 3.
```

Now the **AI Copilot** tab on <http://localhost:3000> can answer questions
about *your* service using logs from *your* app, metrics from *your* app, and
traces from *your* app.

## Cheat sheet: minimum changes to your Spring Boot app

| File | Add |
|---|---|
| `pom.xml` / `build.gradle` | `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `logstash-logback-encoder` |
| `application.yml`          | `management.endpoints.web.exposure.include: prometheus,health,info,metrics` + `management.metrics.tags.service: my-spring-boot-app` |
| `logback-spring.xml`       | JSON `RollingFileAppender` writing to `./logs/${APP_NAME}.json` |
| JVM launch                 | `-javaagent:opentelemetry-javaagent.jar -Dotel.service.name=my-spring-boot-app -Dotel.exporter.otlp.endpoint=http://localhost:4318 -Dotel.traces.exporter=otlp -Dotel.metrics.exporter=none -Dotel.logs.exporter=none` |
