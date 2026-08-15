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
│  │  └─ PhoneLink.Desktop     WPF 桌面端（托盘 / Latest 操作栏 / 暂停接收）
│  └─ android/
│     └─ PhoneLinkAndroid      Kotlin + Compose + CameraX（拍照 / 相册 / 扫码配对 / 上传）
├─ tests/
│  ├─ PhoneLink.Core.Tests
│  ├─ PhoneLink.Transport.Tests
│  ├─ PhoneLink.IntegrationTests
│  └─ PhoneLink.Desktop.Tests
└─ tools/
   ├─ build-desktop.ps1
   ├─ build-android.ps1
   ├─ protocol-smoke-test/     协议冒烟测试
   ├─ qr-show/                 QR 生成/验证 + 设备管理（revoke/restore/reset-cert）
   └─ qr-fullscreen/           全屏显示配对二维码（实机验收用）
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

# 端到端冒烟测试（启动真实 Desktop → 本机上传 → 重启验证持久化）
.\tools\run-smoke-test.ps1
```

## 数据位置

```
%LOCALAPPDATA%\PhoneLink\
├─ data\phonelink.db     元数据（SQLite：设备、token 哈希、传输记录）
├─ inbox\YYYY-MM-DD\     收到的图片（<transferId>.jpg/png/webp）
├─ logs\                 运行日志
└─ temp\                 上传中间文件（成功后移入 inbox）
```

## 配对流程

1. 桌面端启动 → 生成自签证书（持久化）+ mDNS 广告 `_phonelink._tcp.local` + 3 分钟有效配对二维码
2. 手机扫描二维码 → 校验证书指纹（DER SHA-256 钉扎）+ 一次性 token 换长期 Device Token
3. 手机通过 NSD 发现桌面端自动重连；桌面端可随时撤销设备（撤销后手机立即失效）

## 拍照发送流程

1. 手机打开 App：相机实时预览 + 相册入口；竖拿竖拍、横拿横拍（重力传感器驱动方向，无需系统自动旋转）
2. 拍照/选图 → 预览 → 发送：EXIF 方向规范化 + JPEG quality 95 / 最长边 ≤4096 → multipart 上传（metadata 先于 file）
3. 电脑端：Latest 自动显示新图，可打开 / 复制图片 / 打开所在文件夹；失败重试幂等（同 TransferId，不重复落盘）
4. 托盘可暂停接收（手机端提示"桌面端已暂停接收"）；关闭窗口自动隐藏到托盘

## 当前状态

见 [PROJECT_STATUS.md](PROJECT_STATUS.md)。