# Creates the `logs-*` data view in Kibana and diagnoses the log pipeline.
#
# WHY THIS EXISTS:
#   The pipeline is  sample-app -> ./logs/*.json -> Filebeat -> Elasticsearch
#   (index logs-YYYY.MM.DD). Kibana's Discover screen shows NOTHING until a
#   "data view" named logs-* exists -- and nothing in the stack creates one.
#   That empty Discover is what reads as "I can't see logs in Kibana".
#
# USAGE (from repo root, after `docker compose up -d` and your app is running):
#   powershell -ExecutionPolicy Bypass -File demo/setup-kibana.ps1
#
# Host ports come from sentinel-backend/docker-compose.yml (remapped to avoid
# clashes): Elasticsearch 9201, Kibana 5602.

$ErrorActionPreference = 'Stop'
$ES     = $env:ES_URL;     if (-not $ES)     { $ES     = 'http://localhost:9201' }
$KIBANA = $env:KIBANA_URL; if (-not $KIBANA) { $KIBANA = 'http://localhost:5602' }

function Section($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }

# 1. Elasticsearch reachable? -------------------------------------------------
Section "Elasticsearch ($ES)"
try {
    $h = Invoke-RestMethod "$ES/_cluster/health" -TimeoutSec 5
    Write-Host "  cluster status: $($h.status)" -ForegroundColor Green
} catch {
    Write-Host "  UNREACHABLE. Start the stack first:" -ForegroundColor Red
    Write-Host "    docker compose -f sentinel-backend/docker-compose.yml up -d"
    exit 1
}

# 2. Is there a logs-* index, and does it have documents? ---------------------
Section "logs-* index"
$count = 0
try {
    $c = Invoke-RestMethod "$ES/logs-*/_count" -TimeoutSec 5
    $count = $c.count
} catch { }
Write-Host "  documents in logs-*: $count"
if ($count -eq 0) {
    Write-Host "  No log docs in Elasticsearch yet. The data view will still be" -ForegroundColor Yellow
    Write-Host "  created, but Discover stays empty until Filebeat ships logs." -ForegroundColor Yellow
    Write-Host "  Checklist:" -ForegroundColor Yellow
    Write-Host "    - sample-app running and writing demo/sample-app/logs/*.json ?"
    Write-Host "    - filebeat container up?  docker compose ps filebeat"
    Write-Host "    - filebeat logs:          docker compose logs --tail=50 filebeat"
    Write-Host "    - to re-ship already-read files, recreate filebeat so its read"
    Write-Host "      offset registry is reset:  docker compose rm -sf filebeat; docker compose up -d filebeat"
}

# 3. Create (or confirm) the logs-* data view in Kibana ----------------------
Section "Kibana data view ($KIBANA)"
try {
    Invoke-RestMethod "$KIBANA/api/status" -TimeoutSec 5 | Out-Null
} catch {
    Write-Host "  Kibana UNREACHABLE. Give it ~1 min after `up`, then retry." -ForegroundColor Red
    exit 1
}

$headers = @{ 'kbn-xsrf' = 'true'; 'Content-Type' = 'application/json' }

# Already exists?
$exists = $false
try {
    $views = Invoke-RestMethod "$KIBANA/api/data_views" -Headers $headers -TimeoutSec 10
    foreach ($v in $views.data_view) { if ($v.title -eq 'logs-*') { $exists = $true } }
} catch { }

if ($exists) {
    Write-Host "  Data view 'logs-*' already exists. Nothing to do." -ForegroundColor Green
} else {
    $body = @{
        data_view = @{
            title         = 'logs-*'
            name          = 'logs'
            timeFieldName = '@timestamp'
        }
    } | ConvertTo-Json -Depth 5
    try {
        $r = Invoke-RestMethod "$KIBANA/api/data_views/data_view" -Method Post -Headers $headers -Body $body -TimeoutSec 15
        Write-Host "  Created data view 'logs-*' (id: $($r.data_view.id))" -ForegroundColor Green
    } catch {
        Write-Host "  Failed to create data view: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

# 4. Next steps ---------------------------------------------------------------
Section "Next steps"
Write-Host "  1. Open Kibana Discover:  $KIBANA/app/discover"
Write-Host "  2. Pick the 'logs' data view (top-left selector)."
Write-Host "  3. IMPORTANT: set the time range (top-right) wide enough to cover"
Write-Host "     your logs -- default is 'Last 15 minutes'. If your newest log is"
Write-Host "     older than that you'll see nothing. Try 'Last 30 days'."
Write-Host ""