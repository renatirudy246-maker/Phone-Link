# Builds the Phone-Link Windows installer with Inno Setup 7.
# Usage: .\tools\build-windows-installer.ps1 [-PublishDir <path>] [-OutputDir <path>]
#   -PublishDir: existing dotnet publish output (Release, no PDBs). Defaults to a fresh
#                dotnet publish of the Desktop project into a temp staging dir.
param(
    [string]$PublishDir,
    [string]$OutputDir = "artifacts\release\v1.0.0"
)

$ErrorActionPreference = "Stop"

$iscc = "C:\Users\Yy\AppData\Local\Programs\Inno Setup 7\ISCC.exe"
if (-not (Test-Path $iscc)) {
    throw "ISCC.exe not found at $iscc"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$iss = Join-Path $repoRoot "installer\phone-link-setup.iss"

if (-not $PublishDir) {
    $staging = Join-Path $env:TEMP "phonelink-installer-staging"
    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    & dotnet publish "$repoRoot\src\desktop\PhoneLink.Desktop\PhoneLink.Desktop.csproj" -c Release -o $staging --nologo
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed" }
    Get-ChildItem "$staging\*.pdb" | Remove-Item -Force
    $PublishDir = $staging
}

if (-not (Test-Path (Join-Path $PublishDir "PhoneLink.Desktop.exe"))) {
    throw "PhoneLink.Desktop.exe not found in $PublishDir"
}
if (-not (Test-Path (Join-Path $PublishDir "Assets\app.ico"))) {
    throw "Assets\app.ico not found in $PublishDir"
}

New-Item -ItemType Directory -Force -Path (Join-Path $repoRoot $OutputDir) | Out-Null

& $iscc /DAppDir="$PublishDir" "/O$(Join-Path $repoRoot $OutputDir)" $iss
if ($LASTEXITCODE -ne 0) { throw "ISCC compile failed" }

$setup = Join-Path $repoRoot "$OutputDir\Phone-Link-Setup-v1.0.0.exe"
Write-Host "Installer built: $setup ($((Get-Item $setup).Length) bytes)"