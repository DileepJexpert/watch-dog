# SENTINEL sample app

Minimal Spring Boot 3.2 service that emits all three signal types SENTINEL
ingests:

| Signal | Where it lands | Ingested by SENTINEL via |
|---|---|---|
| **JSON logs** | `./logs/app.json` -> Filebeat -> ES `logs-YYYY.MM.DD` | `ElasticsearchConnector` (30s poll) |
| **OTLP traces** | `localhost:4318` -> Jaeger | `JaegerConnector` (60s poll) |
| **Prometheus metrics** | `localhost:1881/actuator/prometheus` -> scraped by Prometheus | `GrafanaConnector` / PromQL (15s poll) |

The endpoints are tuned for SENTINEL's 20 correlation rules:

| Endpoint | What it does | Likely rule it triggers |
|---|---|---|
| `GET /healthy` | 200 OK | none — baseline |
| `GET /fail` | 500 + ERROR log | `HIGH_ERROR_RATE` |
| `GET /db-fail` | 500 + Hikari-style log | `DB_CONNECTIVITY` |
| `GET /slow?ms=2000` | sleeps then 200 | `LATENCY_DEGRADATION` |
| `GET /oom` | 500 + OutOfMemoryError log | `MEMORY_LEAK` |
| `GET /flaky` | 50/50 ok/fail | sustained error rate |

## Run it

### 1. Bring up the infra (if not already)

```powershell
cd C:\dileepkm\Learning\watch-dog\sentinel-backend
docker compose up -d
```

This now includes:
- Jaeger OTLP receiver on `localhost:4318` (HTTP) and `localhost:4317` (gRPC)
- Filebeat tailing `../logs/` and `../demo/sample-app/logs/`

### 2. Start the SENTINEL backend in IntelliJ
`SentinelApplication` with profile `local`. Listens on `:8080`.

### 3. Start the sample app

```powershell
cd C:\dileepkm\Learning\watch-dog\demo\sample-app
mkdir logs -Force
mvn spring-boot:run
```
Listens on `:1881`.

### 4. Drive traffic

```powershell
# from another PowerShell, in demo\sample-app:
.\drive-traffic.ps1 -Duration 90
```

### 5. Watch the dashboard
http://localhost:65111 (Flutter web) or http://localhost:3000 (React).

Within ~30s of traffic starting you should see:
1. **Service Health** picks up `my-spring-boot-app`
2. **Active Incidents** grows — likely `HIGH_ERROR_RATE` and `DB_CONNECTIVITY`
3. **Error Rate** chart climbs
4. **Latency Percentiles** chart shows p95/p99 spiking

## Verify each signal reached its sink

```powershell
# Logs in ES
Invoke-RestMethod "http://localhost:9201/logs-*/_search?q=service.name:my-spring-boot-app&size=3" |
    ConvertTo-Json -Depth 6

# Traces in Jaeger
Start-Process http://localhost:16687  # filter by Service = my-spring-boot-app

# Metrics in Prometheus
Start-Process http://localhost:9091/graph
# Query: rate(http_server_requests_seconds_count{service="my-spring-boot-app",status=~"5.."}[1m])

# Grafana
Start-Process http://localhost:3001  # admin / sentinel
```

## Stop everything

```powershell
# stop the sample app: Ctrl+C in the mvn terminal
# stop SENTINEL backend: stop in IntelliJ
# stop infra:
cd C:\dileepkm\Learning\watch-dog\sentinel-backend
docker compose down
```
