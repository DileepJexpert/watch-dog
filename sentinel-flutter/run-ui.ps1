# Launch the SENTINEL Flutter UI on a FIXED port (no more random ports).
# Usage:  .\run-ui.ps1            -> serves on http://localhost:9099
#         .\run-ui.ps1 -Port 7357 -> override the port
#         .\run-ui.ps1 -Chrome    -> auto-open in Chrome instead of web-server

param(
  [int]$Port = 9099,
  [switch]$Chrome
)

# If the preferred port is taken, walk forward until a free one is found.
function Test-Free([int]$p) {
  -not (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

$tries = 0
while (-not (Test-Free $Port) -and $tries -lt 20) {
  Write-Warning "Port $Port busy, trying $($Port + 1)..."
  $Port++; $tries++
}

$device = if ($Chrome) { "chrome" } else { "web-server" }
Write-Host "Launching SENTINEL UI on http://localhost:$Port  (device=$device)" -Foreground Green

flutter run -d $device --web-port=$Port
