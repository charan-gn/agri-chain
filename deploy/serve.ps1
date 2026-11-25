# Host the AgriChain ledger + web app on Windows.
# Usage: .\deploy\serve.ps1 [-Lan]
param([switch]$Lan)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$venv = Join-Path $root ".venv"
$python = Join-Path $venv "Scripts\python.exe"

if (-not (Test-Path $python)) {
    $base = (Get-Command python -ErrorAction SilentlyContinue).Source
    if (-not $base) { $base = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe" }
    if (-not (Test-Path $base)) { throw "Python 3 not found. Install Python 3.12 and retry." }
    & $base -m venv $venv
}

& $python -m pip install --quiet --upgrade pip
& $python -m pip install --quiet -r (Join-Path $root "ledger\requirements.txt") -r (Join-Path $root "webapp\requirements.txt")

$env:LEDGER_DB = if ($env:LEDGER_DB) { $env:LEDGER_DB } else { Join-Path $root "ledger\ledger.db" }
$env:AGRICHAIN_DEV = if ($env:AGRICHAIN_DEV) { $env:AGRICHAIN_DEV } else { "1" }   # local demo: dev-admin-token
$port = if ($env:PORT) { $env:PORT } else { "8000" }
$webPort = if ($env:WEB_PORT) { $env:WEB_PORT } else { "8080" }
$logDir = Join-Path $env:TEMP "agrichain"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$ledger = Start-Process -FilePath $python `
    -ArgumentList "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", $port `
    -WorkingDirectory (Join-Path $root "ledger") -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "ledger.out") `
    -RedirectStandardError (Join-Path $logDir "ledger.err")

$env:LEDGER_URL = "http://127.0.0.1:$port"
$web = Start-Process -FilePath $python `
    -ArgumentList "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", $webPort `
    -WorkingDirectory (Join-Path $root "webapp") -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "web.out") `
    -RedirectStandardError (Join-Path $logDir "web.err")

Write-Host ""
Write-Host "Dashboard : http://127.0.0.1:$webPort"
Write-Host "Buyer trace: http://127.0.0.1:$webPort/trace"
Write-Host "Ledger API : http://127.0.0.1:$port/health"
if ($Lan) {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.PrefixOrigin -ne "WellKnown" } |
        Select-Object -First 1).IPAddress
    if ($ip) { Write-Host "LAN        : http://${ip}:$webPort" }
}
Write-Host "Logs       : $logDir"
Write-Host "Press Ctrl+C to stop."

try {
    Wait-Process -Id $ledger.Id
} finally {
    Stop-Process -Id $ledger.Id, $web.Id -Force -ErrorAction SilentlyContinue
}
