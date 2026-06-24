# SENTINEL feature smoke-test — run sections one at a time.
# Usage:  cd C:\dileepkm\Learning\watch-dog\demo ;  . .\test-features.ps1
# Then call the functions below, e.g.  Send-Logs ;  Show-Dashboard ;  Resolve-First

$API = "http://localhost:8080"
$ES  = "http://localhost:9201"

function J($u, $m="Get", $b=$null) {
  try {
    if ($b) { Invoke-RestMethod $u -Method $m -ContentType "application/json" -Body $b -TimeoutSec 10 }
    else    { Invoke-RestMethod $u -Method $m -TimeoutSec 10 }
  } catch { Write-Warning "$m $u -> $($_.Exception.Message)" }
}

# 1. INGESTION + CORRELATION ------------------------------------------------
# Push N fresh DB-connection-error logs (>=3 trips CONNECTION_POOL_EXHAUSTION).
function Send-Logs([string]$Svc="demo-test-svc", [int]$N=6) {
  $today = (Get-Date).ToUniversalTime().ToString("yyyy.MM.dd")
  $sb = New-Object System.Text.StringBuilder
  for ($i=0; $i -lt $N; $i++) {
    $ts = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    [void]$sb.AppendLine('{"index":{"_index":"logs-' + $today + '"}}')
    [void]$sb.AppendLine('{"@timestamp":"' + $ts + '","service":{"name":"' + $Svc + '"},"log":{"level":"ERROR"},"message":"HikariPool-1 - Connection is not available, request timed out","error":{"type":"java.sql.SQLException","message":"connection pool exhausted"}}')
    Start-Sleep -Milliseconds 40
  }
  $r = Invoke-RestMethod "$ES/_bulk" -Method Post -ContentType "application/x-ndjson" -Body $sb.ToString()
  Invoke-RestMethod "$ES/logs-*/_refresh" -Method Post | Out-Null
  Write-Host "Indexed $N logs for $Svc (errors=$($r.errors)). Wait ~35s for the poll, then Show-Incidents." -Foreground Green
}

# 2. DASHBOARD (the UI panels) ----------------------------------------------
function Show-Dashboard {
  Write-Host "`n-- service health (top panel) --" -Foreground Cyan
  J "$API/api/dashboard/summary" | Select serviceName,status,errorRate,latencyP95 | Format-Table
  Write-Host "-- stats --" -Foreground Cyan
  J "$API/api/dashboard/stats" | ConvertTo-Json
  Write-Host "-- active incidents --" -Foreground Cyan
  J "$API/api/dashboard/incidents/active" | Select serviceName,title,severity,detectedAt | Format-Table
}

# 3. INCIDENTS --------------------------------------------------------------
function Show-Incidents { (J "$API/api/incidents").content | Select serviceName,severity,status,correlationRule,detectedAt | Format-Table }
function Resolve-First {
  $id = (J "$API/api/incidents").content[0].id
  Write-Host "Resolving incident $id ..." -Foreground Yellow
  J "$API/api/incidents/$id/resolve" "Post"
}

# 4. REMEDIATION ------------------------------------------------------------
function Trigger-Remediation {
  $id = (J "$API/api/incidents").content[0].id
  Write-Host "Triggering remediation for $id ..." -Foreground Yellow
  J "$API/api/remediation/$id/trigger" "Post"
  J "$API/api/remediation/incident/$id" | Format-Table action,status,executedAt
}

# 5. DAILY DIGEST -----------------------------------------------------------
function Trigger-Digest { J "$API/api/digest/daily/trigger" "Post" }

# 6. AI AGENT (only if AGENT_ENABLED=true) ----------------------------------
function Show-AgentStatus { J "$API/api/agent/status" | ConvertTo-Json }
function Ask-Agent([string]$q="What incidents are open and what should I do?") {
  J "$API/api/agent/ask" "Post" (@{question=$q} | ConvertTo-Json)
}

# 7. RULES ------------------------------------------------------------------
function Show-Rules { J "$API/api/rules" }

Write-Host "Loaded. Try:  Send-Logs ; (wait 35s) ; Show-Incidents ; Show-Dashboard" -Foreground Green