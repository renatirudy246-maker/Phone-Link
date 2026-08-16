# Builds a signed Phone-Link Android release APK.
# Reads signing material from the local user-private directory %USERPROFILE%\.phonelink\signing\
# (never from the repository). Requires JDK 17 on PATH.
$ErrorActionPreference = "Stop"

$secretsPath = Join-Path $env:USERPROFILE ".phonelink\signing\keystore-secrets.txt"
if (-not (Test-Path $secretsPath)) {
    throw "Missing signing secrets: $secretsPath (see docs/RELEASE_BUILD.md)"
}

$secrets = Get-Content $secretsPath
$env:PHONELINK_KEYSTORE_PATH = Join-Path $env:USERPROFILE ".phonelink\signing\phone-link-release.keystore"
$env:PHONELINK_KEYSTORE_PASSWORD = ($secrets | Where-Object { $_ -like "store=*" }) -replace "store=", ""
$env:PHONELINK_KEY_ALIAS = ($secrets | Where-Object { $_ -like "alias=*" }) -replace "alias=", ""
$env:PHONELINK_KEY_PASSWORD = ($secrets | Where-Object { $_ -like "key=*" }) -replace "key=", ""

if (-not $env:PHONELINK_KEYSTORE_PASSWORD -or -not $env:PHONELINK_KEY_ALIAS -or -not $env:PHONELINK_KEY_PASSWORD) {
    throw "keystore-secrets.txt is malformed: expected lines 'store=...', 'alias=...', 'key=...'"
}

Push-Location (Join-Path $PSScriptRoot "..\src\android\PhoneLinkAndroid")
try {
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleRelease failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$apk = Join-Path $PSScriptRoot "..\src\android\PhoneLinkAndroid\app\build\outputs\apk\release\app-release.apk"
if (Test-Path $apk) {
    $item = Get-Item $apk
    Write-Host "Signed release APK: $($item.FullName)"
    Write-Host "Size: $($item.Length) bytes"
} else {
    throw "Expected signed APK not found: $apk"
}