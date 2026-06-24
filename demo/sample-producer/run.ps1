# Convenience launcher for the SENTINEL sample producer.
#
# Usage:
#   .\run.ps1                                # 2 events/s, runs forever (Ctrl+C to stop)
#   .\run.ps1 -RatePerSec 5 -DurationSec 90  # 5 events/s for 90 seconds, then exit
#   .\run.ps1 -ServiceName payments-svc      # rename the synthetic service

[CmdletBinding()]
param(
    [string] $ServiceName = 'sample-producer',
    [double] $RatePerSec  = 2,
    [int]    $DurationSec = 0,
    [string] $EsUrl       = 'http://localhost:9201',
    [string] $OtlpUrl     = 'http://localhost:4318/v1/traces',
    [int]    $PromPort    = 1882
)

$env:SERVICE_NAME = $ServiceName
$env:RATE_PER_SEC = "$RatePerSec"
$env:DURATION_SEC = "$DurationSec"
$env:ES_URL       = $EsUrl
$env:OTLP_URL     = $OtlpUrl
$env:PROM_PORT    = "$PromPort"

$producer = Join-Path $PSScriptRoot 'producer.py'
python $producer
