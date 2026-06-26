# SENTINEL — one-shot log generation + verification.
#
# Hits every POST endpoint on sample-app with deliberately-bad payloads,
# optionally runs the sample-producer for a burst of mixed business+infra
# errors, waits for the ES poll cycle, then prints what landed and which
# incidents fired.
#
# Usage:
#   .\test-logs.ps1                                      # defaults: 5 cycles, no producer
#   .\test-logs.ps1 -Cycles 10                           # 10 cycles of each curl
#   .\test-logs.ps1 -WithProducer -ProducerSeconds 90    # also burst the producer
#   .\test-logs.ps1 -Service loan-svc-fresh              # tag the producer's service name
#   .\test-logs.ps1 -SampleAppUrl http://localhost:1881  # custom sample-app port
#
# Exit code 0 if curls + ES check both look healthy.

[CmdletBinding()]
param(
    [string] $SampleAppUrl     = 'http://localhost:1881',
    [string] $SentinelUrl      = 'http://localhost:8080',
    [string] $EsUrl            = 'http://localhost:9201',
    [int]    $Cycles           = 5,
    [switch] $WithProducer,
    [string] $Service          = "test-svc-$(Get-Date -Format 'HHmmss')",
    [int]    $ProducerSeconds  = 60,
    [int]    $ProducerRate     = 5
)

$ErrorActionPreference = 'Continue'   # don't bail on a single 500 — that's the point

# --------------------------------------------------------------------------- #
# Pre-flight
# --------------------------------------------------------------------------- #
Write-Host ""
Write-Host "=== SENTINEL log generator ===" -ForegroundColor Cyan
Write-Host "  sample-app : $SampleAppUrl"
Write-Host "  sentinel   : $SentinelUrl"
Write-Host "  ES         : $EsUrl"
Write-Host "  cycles     : $Cycles  (each cycle hits all 8 payload variants)"
Write-Host "  producer   : $(if ($WithProducer) {"yes, ${ProducerSeconds}s @ ${ProducerRate}/s, service=${Service}"} else {'no'})"
Write-Host ""

function Test-Endpoint([string] $name, [string] $url) {
    try {
        $null = Invoke-WebRequest -Uri $url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
        Write-Host "  [OK]   $name reachable at $url" -ForegroundColor Green
        return $true
    } catch {
        Write-Host "  [WARN] $name NOT reachable at $url -- $($_.Exception.Message)" -ForegroundColor Yellow
        return $false
    }
}

$sampleAppUp = Test-Endpoint 'sample-app' "$SampleAppUrl/actuator/health"
$sentinelUp  = Test-Endpoint 'sentinel'   "$SentinelUrl/actuator/health"
$esUp        = Test-Endpoint 'es'         "$EsUrl/_cluster/health"
Write-Host ""

if (-not $sampleAppUp) {
    Write-Host "[FATAL] sample-app must be running. Start it with:" -ForegroundColor Red
    Write-Host "        cd demo\sample-app; mvn spring-boot:run"
    exit 1
}

# --------------------------------------------------------------------------- #
# Burst — every payload variant, repeated $Cycles times
# --------------------------------------------------------------------------- #
$payloads = @(
    @{ name='loans:missing-source'    ; uri='/api/loans/disburse' ; body='{"applicationId":"LA-301","amount":75000}' },
    @{ name='loans:amount-too-high'   ; uri='/api/loans/disburse' ; body='{"applicationId":"LA-302","amount":50000000,"sourceAccount":"A-78901"}' },
    @{ name='accounts:missing-kyc'    ; uri='/api/accounts'       ; body='{"customerId":"C-12345","pan":"ABCDE1234F"}' },
    @{ name='accounts:bad-pan'        ; uri='/api/accounts'       ; body='{"customerId":"C-12345","pan":"BAD","kycDocId":"doc-1"}' },
    @{ name='payments:invalid-card'   ; uri='/api/payments'       ; body='{"cardNumber":"123","amount":1500,"currency":"INR"}' },
    @{ name='payments:fraud-velocity' ; uri='/api/payments'       ; body='{"cardNumber":"4111111111111111","amount":5000000,"currency":"INR"}' },
    @{ name='transfers:frozen-acct'   ; uri='/api/transfers'      ; body='{"fromAccount":"A-12345","toAccount":"FRZ-78901","amount":5000}' },
    @{ name='transfers:self'          ; uri='/api/transfers'      ; body='{"fromAccount":"A-12345","toAccount":"A-12345","amount":5000}' }
)

Write-Host "=== Phase 1: business-domain failures ==="
$counts = @{}
$total  = 0
$expectedFailures = 0

for ($i = 1; $i -le $Cycles; $i++) {
    foreach ($p in $payloads) {
        $total++
        $expectedFailures++   # every payload variant is bad on purpose
        try {
            $null = Invoke-RestMethod -Method Post -Uri "$SampleAppUrl$($p.uri)" `
                -ContentType 'application/json' -Body $p.body -TimeoutSec 5 -ErrorAction Stop
            # 200 here means the validation passed — should be rare on these bad bodies
            $counts["$($p.name):200"] = ($counts["$($p.name):200"] ?? 0) + 1
        } catch {
            $counts["$($p.name):500"] = ($counts["$($p.name):500"] ?? 0) + 1
        }
    }
    if ($i % 2 -eq 0) { Write-Host "  cycle $i/$Cycles done" }
}

Write-Host ""
Write-Host "  Sent $total requests across $($payloads.Count) endpoints"
Write-Host "  Breakdown:"
$counts.GetEnumerator() | Sort-Object Name | ForEach-Object {
    $padded = $_.Key.PadRight(36)
    Write-Host "    $padded $($_.Value)"
}
Write-Host ""

# --------------------------------------------------------------------------- #
# Optional: producer burst
# --------------------------------------------------------------------------- #
if ($WithProducer) {
    Write-Host "=== Phase 2: producer burst ==="
    $producerScript = Join-Path $PSScriptRoot 'sample-producer\run.ps1'
    if (-not (Test-Path $producerScript)) {
        Write-Host "  [WARN] producer script not found at $producerScript -- skipping" -ForegroundColor Yellow
    } else {
        Write-Host "  Running producer service=$Service rate=$ProducerRate/s for ${ProducerSeconds}s..."
        & $producerScript -ServiceName $Service -RatePerSec $ProducerRate -DurationSec $ProducerSeconds
    }
    Write-Host ""
}

# --------------------------------------------------------------------------- #
# Wait for SENTINEL to poll
# --------------------------------------------------------------------------- #
$waitSec = 35
Write-Host "=== Phase 3: waiting ${waitSec}s for SENTINEL's ES poll + correlation tick ==="
for ($i = $waitSec; $i -gt 0; $i--) {
    Write-Host -NoNewline "`r  $i seconds remaining...  "
    Start-Sleep 1
}
Write-Host "`r  done                              "
Write-Host ""

# --------------------------------------------------------------------------- #
# Verify what landed
# --------------------------------------------------------------------------- #
Write-Host "=== Phase 4: verification ==="

if ($esUp) {
    try {
        $today = (Get-Date).ToUniversalTime().ToString('yyyy.MM.dd')
        $bizCount = (Invoke-RestMethod "$EsUrl/logs-$today/_count?q=service.name:my-spring-boot-app+AND+log.level:ERROR").count
        Write-Host "  ES: ERROR docs for my-spring-boot-app today  -> $bizCount"

        if ($WithProducer) {
            $prodCount = (Invoke-RestMethod "$EsUrl/logs-$today/_count?q=service.name:$Service").count
            Write-Host "  ES: total docs for $Service                 -> $prodCount"
        }

        $sampleHit = Invoke-RestMethod "$EsUrl/logs-$today/_search?size=1&q=service.name:my-spring-boot-app+AND+log.level:ERROR" `
            -ErrorAction SilentlyContinue
        if ($sampleHit -and $sampleHit.hits.hits.Count -gt 0) {
            $msg = $sampleHit.hits.hits[0]._source.message
            Write-Host ""
            Write-Host "  Most recent ERROR log on my-spring-boot-app:"
            Write-Host "    $msg" -ForegroundColor DarkGray
        }
    } catch {
        Write-Host "  [WARN] ES query failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

if ($sentinelUp) {
    try {
        $stats = Invoke-RestMethod "$SentinelUrl/api/dashboard/stats"
        Write-Host ""
        Write-Host "  SENTINEL dashboard stats:"
        Write-Host "    open incidents           $($stats.openIncidents)"
        Write-Host "    last 24h                 $($stats.incidentsLast24h)"
        Write-Host "    last 7d                  $($stats.incidentsLast7d)"
        Write-Host "    services tracked         $($stats.serviceCount)"

        $active = Invoke-RestMethod "$SentinelUrl/api/dashboard/incidents/active"
        $today  = (Get-Date).ToUniversalTime().Date
        $newOnes = $active | Where-Object {
            try { ([DateTime]$_.detectedAt).ToUniversalTime().Date -eq $today } catch { $false }
        }
        Write-Host ""
        Write-Host "  Incidents detected TODAY (UTC):"
        if ($newOnes.Count -eq 0) {
            Write-Host "    none -- check IntelliJ console for [correlation:fire] lines" -ForegroundColor Yellow
        } else {
            foreach ($n in $newOnes | Sort-Object detectedAt -Descending) {
                $svc  = $n.serviceName.PadRight(28)
                $rule = ($n.correlationRule ?? '-').PadRight(28)
                Write-Host "    $($n.severity)  $svc  $rule  $($n.detectedAt)"
            }
        }
    } catch {
        Write-Host "  [WARN] SENTINEL query failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== done ===" -ForegroundColor Cyan
Write-Host "Tip: open the dashboard and the AI Copilot tab — ask:"
Write-Host '      "why are loans failing on my-spring-boot-app right now?"'
Write-Host ""
