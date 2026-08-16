# Phone-Link Android Release Build

## Prerequisites

A release keystore must exist on the **developer machine only** (never in Git):

- Default location: `%USERPROFILE%\.phonelink\signing\phone-link-release.keystore`
- Companion secret file (passwords, generated once): `%USERPROFILE%\.phonelink\signing\keystore-secrets.txt`
- Alias: `phonelink` (RSA 2048, validity 10000 days)

> **WARNING — BACKUP REQUIRED.** The release keystore and its passwords must be
> backed up off-machine (e.g. an encrypted backup you control). Upgrading the
> same app in the future **requires the same signing key**. Losing the keystore
> means the app can never be updated in place again. Never upload it to GitHub
> or any cloud you do not fully control.

## Signing configuration

`app/build.gradle.kts` reads signing material **only from environment variables**:

| Variable | Meaning |
| --- | --- |
| `PHONELINK_KEYSTORE_PATH` | Full path to the `.keystore` file |
| `PHONELINK_KEYSTORE_PASSWORD` | Keystore password |
| `PHONELINK_KEY_ALIAS` | Key alias (default: `phonelink`) |
| `PHONELINK_KEY_PASSWORD` | Key password |

If any variable is missing, release-oriented Gradle tasks fail with a clear
error before doing work. Debug builds and unit tests never require them.

## Build commands

```powershell
# Debug (no signing env needed)
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# Signed release APK
$env:PHONELINK_KEYSTORE_PATH     = "$env:USERPROFILE\.phonelink\signing\phone-link-release.keystore"
$env:PHONELINK_KEYSTORE_PASSWORD = (get-content "$env:USERPROFILE\.phonelink\signing\keystore-secrets.txt" | ? { $_ -like 'store=*' }) -replace 'store=',''
$env:PHONELINK_KEY_ALIAS         = 'phonelink'
$env:PHONELINK_KEY_PASSWORD      = (get-content "$env:USERPROFILE\.phonelink\signing\keystore-secrets.txt" | ? { $_ -like 'key=*' }) -replace 'key=',''
.\gradlew.bat :app:assembleRelease
```

Or use the helper script (same effect):

```powershell
.\tools\build-android-release.ps1
```

## Verify the signed APK

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

## What is never committed

- `*.keystore` / `*.jks` / passwords / `keystore-secrets.txt` (all outside the repo, in `%USERPROFILE%\.phonelink\signing\`)
- Real `signing.properties`