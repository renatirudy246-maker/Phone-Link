# Phone-Link Project Status

## Current Phase
Phase 0 — Repository Bootstrap

## Status
COMPLETED (2026-08-15)

## Last Verified Commit
(将在 git commit 后回填 hash)

## Completed
- [x] 仓库初始化：git init、.gitignore、目录结构（src/desktop、src/android、tests、tools、docs）
- [x] Desktop solution（PhoneLink.slnx）：Core / Transport / Infrastructure / AI / Desktop(WPF) 5 项目
- [x] Core 领域模型：DeviceIdentity、PairedDevice、PairingSession、TransferManifest、TransferRecord、TransferPurpose、TransferStatus、ErrorCodes、ApiError、IAiVisionProvider、AppInfo（协议常量、25MB 限制、MIME 白名单）
- [x] Desktop 最小主窗口（WPF + 简单 MVVM，DataContext=MainViewModel）
- [x] 测试项目 3 个：Core.Tests / Transport.Tests / IntegrationTests（24 个测试）
- [x] Android 工程：Kotlin 2.0.21 + Compose BOM 2024.09.03 + AGP 8.7.2 + Gradle 8.14.3 wrapper
- [x] Android 最小首页（相机预览占位 + 拍照/相册/历史按钮）
- [x] 构建脚本：tools/build-desktop.ps1、tools/build-android.ps1
- [x] 文档：README.md、docs/PROTOCOL.md、docs/SECURITY.md、docs/TESTING.md

## Verification
- Desktop build: 成功（0 warning / 0 error，net10.0 + net10.0-windows）
- Desktop tests: 24/24 通过（Core 21、Transport 2、Integration 1）
- Android build: 成功（:app:assembleDebug，APK 9.4 MB）
- Manual test: Desktop 启动到主窗口（见下方记录）；Android 需真机/模拟器（本机无，留待 Phase 3 前验证）

## 环境决策（§0.12 小决策记录）
- .NET SDK 10.0.302 → TargetFramework net10.0（LTS）
- Gradle 8.14.3 + AGP 8.7.2 + Kotlin 2.0.21 + compose plugin 2.0.21（全部来自本机缓存，已验证可构建）
- JDK 17（~/.gradle/jdks）用于 Android 构建（AGP 8.x 要求）
- Android SDK：D:\AndroidEnv\Sdk（platforms 35/36，compileSdk=35）
- 解决方案文件用 .slnx（.NET 10 默认格式）
- minSdk 26 / targetSdk 35；android:theme 用系统 Material Light NoActionBar（Phase 0 不引入 appcompat 依赖）

## Known Issues
- 本机无 adb / 模拟器 / 真机，Android 启动到主界面未做手工验证（仅构建通过）
- WPF 最小窗口尚未做托盘、Generic Host（Phase 1 引入）
- 暂无 git commit（待本阶段验收后统一提交）

## Next Action
- 提交 Phase 0（建议 commit: `phase-0: bootstrap phone-link workspace`）
- 用户确认后进入 Phase 1（Windows Local Receiver：Kestrel HTTPS、/v1/health、SQLite、Transfer Service、smoke test）