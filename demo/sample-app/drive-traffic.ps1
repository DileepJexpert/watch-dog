# Drives synthetic traffic at the sample-app to make SENTINEL's correlation
# rules fire. Hit Ctrl+C to stop.
#
# Usage:
#   .\drive-traffic.ps1                      # default mix
#   .\drive-traffic.ps1 -Duration 120        # run for 120 seconds
#   .\drive-traffic.ps1 -BaseUrl http://localhost:8081 -Duration 60

[CmdletBinding()]
param(
    [string] $BaseUrl  = 'http://localhost:1881',
    [int]    $Duration = 60
)

Write-Host "[drive] hitting $BaseUrl for $Duration seconds"
Write-Host "[drive] mix: 5x /healthy  4x /fail  2x /db-fail  1x /slow  1x /oom"

$endpoints = @(
    @{ url='/healthy'; weight=5 },
    @{ url='/fail';    weight=4 },
    @{ url='/db-fail'; weight=2 },
    @{ url='/slow?ms=1500'; weight=1 },
    @{ url='/oom';     weight=1 }
)

# Build weighted bag
$bag = @()
foreach ($e in $endpoints) {
    for ($i = 0; $i -lt $e.weight; $i++) { $bag += $e.url }
}

$end = (Get-Date).AddSeconds($Duration)
$counts = @{}
$req = 0

while ((Get-Date) -lt $end) {
    $path = $bag | Get-Random
    $req++
    try {
        Invoke-RestMethod "$BaseUrl$path" -TimeoutSec 5 | Out-Null
        $key = "ok:$path"
    } catch {
        $key = "err:$path"
    }
    if (-not $counts.ContainsKey($key)) { $counts[$key] = 0 }
    $counts[$key]++

    if ($req % 20 -eq 0) {
        Write-Host "[drive] $req requests so far"
    }
    Start-Sleep -Milliseconds 200
}

Write-Host ""
Write-Host "[drive] done — $req total requests"
$counts.GetEnumerator() | Sort-Object Name | ForEach-Object {
    $padded = $_.Key.PadRight(30)
    Write-Host "  $padded $($_.Value)"
}
