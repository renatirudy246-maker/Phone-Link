# 端到端冒烟测试：启动真实 Desktop 应用 → 本机上传真实图片 → 验证落盘/哈希/拒绝路径。
# 用法：
#   .\tools\run-smoke-test.ps1 [-Port 8484] [-NoRestartCheck]
param(
    [int]$Port = 8484,
    [switch]$NoRestartCheck
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Host "==> Building desktop" -ForegroundColor Cyan
dotnet build (Join-Path $root "PhoneLink.slnx") | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$exe = Join-Path $root "src\desktop\PhoneLink.Desktop\bin\Debug\net10.0-windows\PhoneLink.Desktop.exe"
$app = Start-Process $exe -PassThru

try {
    Write-Host "==> Waiting for receiver on port $Port" -ForegroundColor Cyan
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 1000
        if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
            $ready = $true
            break
        }
        if ($app.HasExited) {
            Write-Host "App exited early with code $($app.ExitCode)" -ForegroundColor Red
            exit 1
        }
    }
    if (-not $ready) { Write-Host "Receiver did not come up in time" -ForegroundColor Red; exit 1 }

    Write-Host "==> Running protocol smoke test" -ForegroundColor Cyan
    & dotnet run --project (Join-Path $root "tools\protocol-smoke-test") -- --base-url "https://127.0.0.1:$Port"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (-not $NoRestartCheck) {
        Write-Host "==> Restarting app to verify SQLite history persists" -ForegroundColor Cyan
        Stop-Process -Id $app.Id -Force
        Start-Sleep -Seconds 2
        $app = Start-Process $exe -PassThru

        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Milliseconds 1000
            if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
                $ready = $true
                break
            }
        }
        if (-not $ready) { Write-Host "Receiver did not come back up after restart" -ForegroundColor Red; exit 1 }

        $latest = Get-ChildItem (Join-Path $env:LOCALAPPDATA "PhoneLink\inbox") -Recurse -Filter "*.jpg" |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $latest) {
            Write-Host "No jpg found in inbox for restart check" -ForegroundColor Red
            exit 1
        }
        $persistId = $latest.BaseName

        Write-Host "==> Verifying history after restart (transfer $persistId)" -ForegroundColor Cyan
        & dotnet run --project (Join-Path $root "tools\protocol-smoke-test") -- `
            --base-url "https://127.0.0.1:$Port" --expect-id $persistId
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host ""
    Write-Host "==> Smoke test completed successfully" -ForegroundColor Green
}
finally {
    if (-not $app.HasExited) {
        Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
    }
}