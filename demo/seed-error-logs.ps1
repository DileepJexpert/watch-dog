# Inject fake ERROR log entries into Elasticsearch so SENTINEL's
# ElasticsearchConnector picks them up on its next poll (every 30s).
#
# Usage:   .\demo\seed-error-logs.ps1 [-Service <name>] [-Count <n>]
# Default: -Service payments-svc -Count 15
#
# After this runs, watch for:
#   - GET /api/dashboard/incidents/active  -> new OPEN incident within ~30s
#   - The AI Copilot can answer "what just happened to <service>?"

[CmdletBinding()]
param(
    [string] $Service = 'payments-svc',
    [int]    $Count   = 15
)

$EsUrl = if ($env:ES_URL) { $env:ES_URL } else { 'http://localhost:9201' }
$Today = (Get-Date).ToUniversalTime().ToString('yyyy.MM.dd')
$Index = "logs-$Today"

Write-Host "[seed] injecting $Count ERROR docs into $EsUrl/$Index for service=$Service"

$errMsgs = @(
    'JdbcSQLException: Connection is not available, request timed out after 30000ms',
    'HikariPool-1 - Connection is not available, request timed out (active=20, idle=0)',
    'java.sql.SQLException: Cannot get a connection, pool error Timeout waiting for idle object',
    'OutOfMemoryError: Java heap space',
    'Connection refused: payments-db:5432',
    'java.net.SocketTimeoutException: Read timed out'
)

$sb = [System.Text.StringBuilder]::new()
for ($i = 1; $i -le $Count; $i++) {
    $msg = $errMsgs | Get-Random
    $ts = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    [void] $sb.AppendLine("{`"index`":{`"_index`":`"$Index`"}}")
    $doc = [pscustomobject]@{
        '@timestamp' = $ts
        service      = @{ name = $Service }
        log          = @{ level = 'ERROR' }
        message      = $msg
        error        = @{ type = 'java.sql.SQLException'; message = $msg }
    } | ConvertTo-Json -Compress -Depth 5
    [void] $sb.AppendLine($doc)
}

try {
    $resp = Invoke-RestMethod -Method Post -Uri "$EsUrl/_bulk" `
        -ContentType 'application/x-ndjson' -Body $sb.ToString()
    $itemCount = if ($resp.items) { $resp.items.Count } else { 0 }
    Write-Host "[seed] errors= $($resp.errors) items= $itemCount"
} catch {
    Write-Error "[seed] failed: $_"
    exit 1
}

Write-Host "[seed] done — SENTINEL will pick these up within ~30s (poll-interval-seconds)"
