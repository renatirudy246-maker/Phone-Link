# Build the Android app (debug).
param(
    [string]$Task = "assembleDebug"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root "src\android\PhoneLinkAndroid"

$jdks = Join-Path $env:USERPROFILE ".gradle\jdks"
$jdk = Get-ChildItem $jdks -Directory -Filter "*17*" |
    ForEach-Object { Get-ChildItem $_.FullName -Directory | Select-Object -First 1 } |
    Select-Object -First 1

if ($jdk) {
    $env:JAVA_HOME = $jdk.FullName
    Write-Host "==> Using JDK: $($env:JAVA_HOME)" -ForegroundColor Cyan
}

Write-Host "==> Gradle :app:$Task" -ForegroundColor Cyan
& (Join-Path $androidDir "gradlew.bat") ":app:$Task" --no-daemon
exit $LASTEXITCODE