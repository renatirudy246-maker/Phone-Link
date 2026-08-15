# 端到端冒烟测试：启动真实 Desktop 应用（含 mDNS 发布）→ 真实配对 → 本机上传真实图片 → 验证落盘/哈希/拒绝路径 → 重启验证持久化。
# 用法：
#   .\tools\run-smoke-test.ps1 [-Port 8484] [-NoRestartCheck]
param(
    [int]$Port = 8484,
    [switch]$NoRestartCheck
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$testDataDir = Join-Path $env:TEMP "phonelink-smoke-data"
$pairFile = Join-Path $env:TEMP "phonelink-smoke-pair.txt"

Write-Host "==> Building desktop" -ForegroundColor Cyan
dotnet build (Join-Path $root "PhoneLink.slnx") | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 清理上一次测试数据（模拟全新环境，确保 dev-token.txt 不存在）
if (Test-Path $testDataDir) { Remove-Item -Recurse -Force $testDataDir }
if (Test-Path $pairFile) { Remove-Item -Force $pairFile }

$exe = Join-Path $root "src\desktop\PhoneLink.Desktop\bin\Debug\net10.0-windows\PhoneLink.Desktop.exe"
$env:PHONELINK_DATA_DIR = $testDataDir
$env:PHONELINK_TEST_PAIRING_OUTPUT = $pairFile
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
    if (-not (Test-Path $pairFile)) { Write-Host "Pairing payload not produced (PHONELINK_TEST_PAIRING_OUTPUT)" -ForegroundColor Red; exit 1 }

    Write-Host "==> Running protocol smoke test (real pairing)" -ForegroundColor Cyan
    & dotnet run --project (Join-Path $root "tools\protocol-smoke-test") -- `
        --base-url "https://127.0.0.1:$Port" --pair-file $pairFile --data-dir $testDataDir
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (-not $NoRestartCheck) {
        Write-Host "==> Restarting app to verify SQLite history + paired device persist" -ForegroundColor Cyan
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

        $latest = Get-ChildItem (Join-Path $testDataDir "inbox") -Recurse -Filter "*.jpg" |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $latest) {
            Write-Host "No jpg found in inbox for restart check" -ForegroundColor Red
            exit 1
        }
        $persistId = $latest.BaseName

        Write-Host "==> Verifying history after restart (transfer $persistId)" -ForegroundColor Cyan
        & dotnet run --project (Join-Path $root "tools\protocol-smoke-test") -- `
            --base-url "https://127.0.0.1:$Port" --pair-file $pairFile --data-dir $testDataDir --expect-id $persistId
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host ""
    Write-Host "==> Smoke test completed successfully" -ForegroundColor Green
}
finally {
    Remove-Item Env:\PHONELINK_DATA_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:\PHONELINK_TEST_PAIRING_OUTPUT -ErrorAction SilentlyContinue
    if (-not $app.HasExited) {
        Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
    }
}