# Build and test all .NET desktop projects.
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sln = Join-Path $root "PhoneLink.slnx"

if (-not $SkipBuild) {
    Write-Host "==> Building desktop solution" -ForegroundColor Cyan
    dotnet build $sln
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "==> Running desktop tests" -ForegroundColor Cyan
dotnet test $sln --no-build
exit $LASTEXITCODE