# Phone-Link

手机拍下不会的题目，电脑端几乎零操作收到图片，并可继续交给 AI 解题。

```
手机拍题 → 电脑自动收到 → 可立即问 AI
```

## 仓库结构

```
├─ AGENT_BUILD_SPEC.md      构建规范（执行依据）
├─ PROJECT_STATUS.md        当前进度状态
├─ docs/
│  ├─ PROTOCOL.md           局域网传输协议
│  ├─ SECURITY.md           安全基线
│  └─ TESTING.md            测试矩阵与验证方式
├─ src/
│  ├─ desktop/
│  │  ├─ PhoneLink.Core        领域模型 / 接口（无 UI 依赖）
│  │  ├─ PhoneLink.Transport   局域网接收服务（Kestrel/HTTPS/mDNS）
│  │  ├─ PhoneLink.Infrastructure  SQLite / 文件系统 / 安全存储 / 日志
│  │  ├─ PhoneLink.AI          AI Vision Provider
│  │  └─ PhoneLink.Desktop     WPF 桌面端（托盘 / 预览）
│  └─ android/
│     └─ PhoneLinkAndroid      Kotlin + Compose + CameraX
├─ tests/
│  ├─ PhoneLink.Core.Tests
│  ├─ PhoneLink.Transport.Tests
│  └─ PhoneLink.IntegrationTests
└─ tools/
   ├─ build-desktop.ps1
   ├─ build-android.ps1
   └─ protocol-smoke-test/     协议冒烟测试（Phase 1）
```

## 技术栈

| 端 | 技术 |
| --- | --- |
| Windows | .NET 10 · WPF · MVVM · ASP.NET Core Kestrel · SQLite · DPAPI |
| Android | Kotlin · Jetpack Compose · CameraX · OkHttp · Android Keystore |

## 构建

```powershell
# Desktop: 构建 + 测试
.\tools\build-desktop.ps1

# Android: debug 构建（自动使用本机 JDK 17）
.\tools\build-android.ps1
```

## 当前状态

见 [PROJECT_STATUS.md](PROJECT_STATUS.md)。