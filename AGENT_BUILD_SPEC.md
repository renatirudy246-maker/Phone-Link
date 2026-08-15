# Phone-Link — Agent Build Specification

> **用途**：把本文件放到项目仓库根目录，交给 Coding Agent。  
> **Agent 的第一条指令**：完整阅读本文件，然后从 Phase 0 开始执行。不要跳阶段，不要先实现“未来功能”。  
> **项目代号**：Phone-Link  
> **核心场景**：手机拍下不会的题目，电脑端几乎零操作收到图片，并可继续交给 AI 解题。  
> **原则**：文件传输只是底层能力，真正目标是缩短“手机现实世界内容 → 电脑工作流 / AI”的路径。

---

# 0. Agent 执行规则

## 0.1 必须遵守

1. 先检查当前仓库状态、已有代码、工具链和未提交修改。
2. 如果仓库为空，按本文件建议结构初始化。
3. 如果仓库已有内容，优先复用，不得为了“架构更漂亮”无理由重写可用代码。
4. **一次只执行一个 Phase。**
5. 当前 Phase 未达到验收标准，不得开始下一个 Phase。
6. 每完成一个 Phase：
   - 编译全部受影响项目；
   - 运行自动化测试；
   - 做必要的本机运行验证；
   - 更新 `PROJECT_STATUS.md`；
   - 记录修改文件、测试结果、已知问题和下一步。
7. 不允许用大段 mock 假装功能完成。允许短期测试桩，但最终验收路径必须经过真实实现。
8. 不要留下静默失败。网络、配对、上传、图片处理、AI 调用必须有明确状态和错误信息。
9. 不要在日志中输出 API Key、配对 Token、长期设备密钥、完整 Authorization Header。
10. 所有外部依赖必须：
    - 选择维护活跃的稳定版本；
    - 固定版本；
    - 说明用途；
    - 避免为了一个小功能引入大型框架。
11. 优先保证：
    - 能运行；
    - 稳定；
    - 可测试；
    - 低操作成本；
    - 再考虑动画和视觉细节。
12. 如果遇到本文件没有覆盖的小决策，选择**最小、最稳、最容易替换**的方案，并写入 `PROJECT_STATUS.md`。
13. 不要因为小问题反复向用户提问。能通过工程判断解决的直接解决；只有涉及硬件、账号、真实手机操作等无法代替用户完成的步骤才请求用户验证。
14. 禁止在 MVP 阶段实现：
    - 云端中继；
    - 用户账号系统；
    - 社交功能；
    - 错题社区；
    - 多人协作；
    - 复杂文件管理器；
    - iOS 客户端；
    - 自动同步整个相册；
    - OCR 错题知识图谱；
    - 与微信/QQ集成。
15. 项目必须保持可扩展，但**不要为了未来扩展提前建设复杂微服务或插件系统**。

---

# 1. 产品定义

## 1.1 用户痛点

当前复习流程：

`手机拍题 → 相册 → 裁切 → 保存 → 微信文件传输助手 → 电脑微信 → 打开/下载 → 拖进 AI → 输入问题 → 查看解析`

操作链过长。

Phone-Link 要把它缩短为：

`手机拍题 → 电脑自动收到 → 可立即问 AI`

最终理想形态：

`手机拍题 → 电脑自动进入 AI 解题 → 直接显示解析`

---

## 1.2 MVP 成功标准

MVP 不以“功能数量”为成功标准，而以下列体验为成功标准：

1. 手机与 Windows 电脑首次通过二维码完成安全配对。
2. 后续在同一局域网时能自动找到已配对电脑。
3. 手机打开 App 后可以快速拍照。
4. 拍照后最多经过一次确认即可发送。
5. 电脑无需打开微信、浏览器上传页或手动下载文件。
6. 图片到达电脑后立即显示预览。
7. 原图不会被聊天软件二次压缩。
8. 发送失败时用户明确知道原因，并能重试。
9. AI 模块接入后，可对刚收到的图片一键“问 AI”。
10. 整套流程明显短于微信文件传输助手。

---

# 2. MVP 范围

## 2.1 第一阶段支持平台

### Desktop
- Windows 11
- x64
- 原生桌面应用
- 后台常驻 + 托盘
- 局域网接收服务

### Mobile
- **Android First**
- Kotlin
- Jetpack Compose
- CameraX

说明：

- 协议必须与手机平台无关。
- iOS 放在 MVP 完成之后实现。
- 不得把 Android 专属字段写死到传输协议核心模型中。

---

# 3. 推荐技术架构

> 如果当前仓库已经存在成熟技术栈，不要无理由迁移；否则采用下述方案。

## 3.1 Windows Desktop

- C#
- 当前稳定 LTS .NET
- WPF
- MVVM
- Generic Host / DI
- ASP.NET Core Kestrel 作为本机局域网接收服务
- SQLite 用于本地元数据
- Windows DPAPI / Credential Manager 用于敏感信息
- 系统托盘常驻

为什么 MVP 选 WPF：

- Windows 工具类软件成熟稳定；
- 工程复杂度低于重新建设跨平台桌面壳；
- 与本地文件、托盘、网络服务、Windows API 集成简单；
- 后续如果需要，可单独升级视觉层，而不影响 Core/Transport。

---

## 3.2 Android

- Kotlin
- Jetpack Compose
- CameraX
- Android Keystore
- Android NSD / mDNS
- 协程
- OkHttp 或等价稳定 HTTP Client

---

## 3.3 网络协议

采用：

- 局域网
- HTTPS
- REST 上传
- mDNS / NSD 发现
- QR 首次配对
- TLS Certificate Fingerprint Pinning
- Bearer Device Token

**禁止：**
- UPnP 自动开放公网端口
- 把电脑服务暴露到公网
- MVP 阶段使用中央服务器转发图片
- 明文传输长期鉴权 Token

---

# 4. 总体架构

```text
┌──────────────────────────┐
│       Android Phone      │
│                          │
│ Camera / Crop / Preview  │
│ Pairing / Discovery      │
│ Upload Client            │
└────────────┬─────────────┘
             │
             │ Local Wi-Fi / HTTPS
             │
┌────────────▼─────────────┐
│       Windows PC         │
│                          │
│ Local HTTPS Receiver     │
│ Pairing Service          │
│ Transfer Service         │
│ Inbox / Preview UI       │
│ Local Storage            │
│ AI Bridge                │
└────────────┬─────────────┘
             │
             │ optional
             ▼
       AI Vision Provider
```

---

# 5. 建议仓库结构

如果仓库为空，建立：

```text
PhoneLink/
├─ AGENT_BUILD_SPEC.md
├─ README.md
├─ PROJECT_STATUS.md
├─ .gitignore
├─ docs/
│  ├─ PROTOCOL.md
│  ├─ SECURITY.md
│  └─ TESTING.md
│
├─ src/
│  ├─ desktop/
│  │  ├─ PhoneLink.Desktop/
│  │  ├─ PhoneLink.Core/
│  │  ├─ PhoneLink.Transport/
│  │  ├─ PhoneLink.Infrastructure/
│  │  └─ PhoneLink.AI/
│  │
│  └─ android/
│     └─ PhoneLinkAndroid/
│
├─ tests/
│  ├─ PhoneLink.Core.Tests/
│  ├─ PhoneLink.Transport.Tests/
│  └─ PhoneLink.IntegrationTests/
│
└─ tools/
   └─ protocol-smoke-test/
```

职责：

### `PhoneLink.Desktop`
- WPF View
- ViewModel
- Tray
- 页面导航
- UI 状态

### `PhoneLink.Core`
- 领域模型
- 接口
- 状态机
- 不依赖 WPF
- 不依赖具体网络框架

### `PhoneLink.Transport`
- Kestrel
- HTTPS
- Pairing
- Upload API
- mDNS 发布
- 请求鉴权

### `PhoneLink.Infrastructure`
- SQLite
- 文件系统
- DPAPI / Credential Manager
- 日志
- 配置

### `PhoneLink.AI`
- AI Provider 接口
- OpenAI-Compatible Provider
- Prompt 模板
- 请求状态

### Android
- Camera
- Pairing
- Discovery
- Send
- Local secure storage
- Crop

---

# 6. 核心领域模型

所有模型命名可以根据现有代码调整，但职责必须保留。

## 6.1 DeviceIdentity

```text
DeviceId
DisplayName
Platform
CreatedAt
PublicFingerprint
```

---

## 6.2 PairedDevice

```text
DeviceId
DisplayName
Platform
AuthTokenReference
CertificateFingerprint
LastSeenAt
LastKnownEndpoint
IsTrusted
```

注意：

- 数据库中不要保存裸敏感 Token；
- 应保存 Secret Reference 或经系统安全能力保护的数据。

---

## 6.3 PairingSession

```text
SessionId
OneTimeToken
ExpiresAt
DesktopDeviceId
DesktopDisplayName
Endpoint
CertificateFingerprint
Consumed
```

一次性 Token 必须：
- 使用安全随机数；
- 短时有效；
- 成功配对后立即失效；
- 不允许重复消费。

---

## 6.4 TransferManifest

```text
TransferId
SenderDeviceId
OriginalFileName
MimeType
FileSize
Width
Height
Sha256
CapturedAt
SentAt
Purpose
```

`Purpose` 初始支持：

```text
Photo
Question
File
```

MVP 主要使用 `Question` 和 `Photo`。

---

## 6.5 TransferRecord

```text
TransferId
SenderDeviceId
LocalFilePath
ThumbnailPath
ReceivedAt
Status
ErrorCode
```

状态至少支持：

```text
Receiving
Completed
Failed
Deleted
```

---

# 7. 本地存储

默认目录：

```text
%LOCALAPPDATA%\PhoneLink\
```

建议：

```text
PhoneLink/
├─ data/
│  └─ phonelink.db
├─ inbox/
│  └─ 2026-08-15/
│     ├─ <transfer-id>.jpg
│     └─ ...
├─ thumbnails/
├─ logs/
└─ temp/
```

要求：

1. 服务器永远自己生成本地文件名。
2. 不能信任手机传来的文件路径和文件名。
3. 防止 `../` 路径穿越。
4. 上传先写 temp，再进行校验，再原子移动到 inbox。
5. 文件写入失败不得产生 Completed 记录。
6. 默认图片大小上限：
   - 单张 25 MB。
7. MVP 支持 MIME：
   - image/jpeg
   - image/png
   - image/webp
8. MIME 与实际文件头不一致时拒绝。

---

# 8. 配对设计

## 8.1 首次配对

Windows：

```text
设置
  └─ 添加手机
       └─ 显示二维码
```

QR Payload 逻辑内容：

```text
phonelink://pair?
v=1
&deviceId=<desktop-device-id>
&name=<desktop-name>
&host=<local-ip>
&port=<https-port>
&token=<one-time-token>
&fp=<certificate-sha256-fingerprint>
```

实际编码可以使用 compact JSON + Base64URL，避免 URL 太长。

---

## 8.2 Android 扫描二维码

流程：

```text
Scan QR
  ↓
Parse payload
  ↓
Validate v / token / endpoint
  ↓
Connect HTTPS
  ↓
Verify certificate fingerprint
  ↓
POST /v1/pair
  ↓
Receive long-lived device token
  ↓
Store in Android Keystore-backed storage
  ↓
Connected
```

---

## 8.3 后续重连

电脑广播：

```text
_phonelink._tcp.local
```

TXT 信息只能放非敏感数据，例如：

```text
version=1
deviceId=...
name=...
```

手机发现相同 `deviceId` 后：

1. 获取当前 IP / Port；
2. TLS Fingerprint 校验；
3. 使用已保存 Device Token；
4. 调用 health；
5. 标记在线。

如果 mDNS 不可用：
- 回退最后已知地址；
- 再失败则提示重新配对；
- 不做公网扫描。

---

# 9. API V1

API 前缀：

```text
/v1
```

---

## 9.1 Health

```http
GET /v1/health
```

响应示例：

```json
{
  "protocolVersion": 1,
  "deviceId": "desktop-uuid",
  "deviceName": "Jacob-PC",
  "status": "ok"
}
```

配对后 health 需要认证。

可以保留一个非常有限的 pre-pair health，仅返回协议版本，不返回敏感信息。

---

## 9.2 Pair

```http
POST /v1/pair
```

Request：

```json
{
  "oneTimeToken": "...",
  "mobileDeviceId": "...",
  "mobileDeviceName": "...",
  "platform": "android"
}
```

Response：

```json
{
  "deviceToken": "...",
  "desktopDeviceId": "...",
  "protocolVersion": 1
}
```

要求：

- Token 仅在 HTTPS 下发送；
- pairing token 单次使用；
- 长期 token 使用至少 256-bit 随机值；
- 服务端只存安全形式。

---

## 9.3 Upload Transfer

```http
POST /v1/transfers
Authorization: Bearer <device-token>
Content-Type: multipart/form-data
```

包含：

```text
metadata = JSON
file     = binary
```

成功：

```json
{
  "transferId": "...",
  "status": "completed",
  "receivedAt": "..."
}
```

---

## 9.4 Transfer Status

```http
GET /v1/transfers/{transferId}
Authorization: Bearer <device-token>
```

用于网络异常后的状态确认和未来断点扩展。

---

# 10. 上传完整性

每张图片必须：

1. 手机计算 SHA-256；
2. metadata 包含 hash；
3. PC 完成写入后重新计算；
4. 不一致则：
   - 删除 temp 文件；
   - 标记失败；
   - 返回完整性错误。

客户端不要因为 HTTP 连接中断就立即认为失败。

如果请求结果不确定：

```text
GET /v1/transfers/{id}
```

确认服务端状态，避免重复上传。

---

# 11. Windows UI

## 11.1 MVP 主窗口

布局不要复杂。

至少包含：

```text
Phone-Link

[ Online: Pixel / Android ]

Latest
┌─────────────────────────┐
│                         │
│     received image      │
│                         │
└─────────────────────────┘

[Open] [Copy] [Ask AI]

Recent
- 14:08 question.jpg
- 14:03 question.jpg
```

---

## 11.2 托盘

支持：

```text
Open Phone-Link
Pause Receiving
Pair New Phone
Open Inbox Folder
Exit
```

应用关闭主窗口时默认：
- 隐藏到托盘；
- 接收服务继续运行。

真正退出才停止服务。

---

## 11.3 收到图片后的行为

收到成功事件：

1. 保存图片；
2. 生成缩略图；
3. 写入 SQLite；
4. 更新 Recent；
5. Latest 显示图片；
6. 如果主窗口隐藏：
   - 显示轻量桌面通知；
7. 不强抢前台焦点；
8. 不影响用户当前窗口输入。

---

# 12. Android UI

## 12.1 首页优先级

手机端不是“文件管理器”。

默认首页应直接是：

```text
┌────────────────────┐
│ Jacob-PC   ● Online│
│                    │
│    Camera Preview  │
│                    │
│                    │
│        ●           │
│                    │
│ [Gallery] [History]│
└────────────────────┘
```

用户启动 App 后，最重要动作是：
**拍照。**

---

## 12.2 拍照流程

MVP：

```text
Camera
 ↓
Capture
 ↓
Preview
 ↓
Send
```

Preview 页：

```text
[ Retake ]

┌────────────────────┐
│       Photo        │
└────────────────────┘

[Crop]      [Send to PC]
```

后续 Smart Crop 完成后：

```text
Camera
 ↓
Capture
 ↓
Auto Crop
 ↓
Preview
 ↓
Send
```

---

# 13. 图片处理

## 13.1 第一版

先保证：
- CameraX 正常拍摄；
- EXIF 旋转正确；
- 图片不过度压缩；
- 发送前能预览；
- 可进行基础手动裁切。

---

## 13.2 Smart Crop

Phase 4 再实现。

目标：

1. 检测纸张/屏幕中的主要四边形；
2. 自动计算透视；
3. Perspective Transform；
4. 显示自动裁切结果；
5. 用户可以：
   - 接受；
   - 调整四个角；
   - 使用原图。

必须始终保留原始拍摄图片直到发送流程结束。

自动裁切失败不能阻止发送。

---

## 13.3 后续题目区域识别

**不属于首版 Smart Crop 验收。**

后续可以：

```text
OCR
 ↓
Layout Analysis
 ↓
Question Block Detection
 ↓
多个题块
 ↓
用户点某一道
```

例如一页里识别第 13 / 14 / 15 题。

这是 Post-MVP 功能，不要提前实现。

---

# 14. AI Bridge

AI 功能在基础传图稳定后再实现。

## 14.1 目标

电脑收到一道题后：

```text
[Ask AI]
```

点击即可把：
- 图片
- 固定 Prompt
发送到 Vision Model。

---

## 14.2 抽象接口

Core 中定义：

```text
IAiVisionProvider
```

能力：

```text
SolveQuestionAsync(image, prompt, cancellationToken)
TestConnectionAsync()
```

不要让 UI 直接调用 HTTP Provider。

---

## 14.3 第一 Provider

实现：

**OpenAI-Compatible Vision Provider**

配置：

```text
Base URL
API Key
Model
Timeout
```

这样后续可以兼容多个 OpenAI 风格 API。

不得把 API Key 写入：
- appsettings.json
- Git
- 日志
- SQLite 明文字段

使用 Windows Credential Manager / DPAPI。

---

## 14.4 默认解题 Prompt

默认：

```text
请解答图片中的题目。

要求：
1. 先准确识别题目内容。
2. 给出最终答案。
3. 分步骤解释推导过程。
4. 涉及公式时说明变量含义。
5. 指出这道题考查的主要知识点。
6. 单独说明最容易出错或混淆的地方。
7. 如果图片内容不完整或无法确定，不要猜，明确指出缺失信息。
```

用户后续可以修改 Prompt Template。

---

## 14.5 AI 状态

UI 状态至少：

```text
Idle
Preparing
Uploading
Thinking
Completed
Failed
Cancelled
```

重复点 Ask AI 时：
- 不创建无限并发；
- 当前请求可取消；
- UI 明确显示状态。

---

# 15. 后续“连续刷题模式”

MVP AI 完成后再做。

手机连续拍题：

```text
Question #1 -> PC
Question #2 -> PC
Question #3 -> PC
```

电脑：

```text
Session: 2026-08-15

#1 Completed
#2 Thinking
#3 Queued
```

未来可保存：
- 图片
- AI 解析
- 时间
- 学科
- 知识点

但当前禁止做完整错题本系统。

---

# 16. 错误码

定义统一错误模型：

```json
{
  "code": "TRANSFER_HASH_MISMATCH",
  "message": "File integrity check failed.",
  "retryable": true
}
```

至少包含：

```text
PAIR_TOKEN_INVALID
PAIR_TOKEN_EXPIRED
PAIR_ALREADY_USED
AUTH_INVALID
DEVICE_REVOKED
UNSUPPORTED_PROTOCOL
FILE_TOO_LARGE
UNSUPPORTED_MEDIA_TYPE
TRANSFER_HASH_MISMATCH
DISK_WRITE_FAILED
NETWORK_TIMEOUT
DESKTOP_OFFLINE
AI_AUTH_FAILED
AI_TIMEOUT
AI_PROVIDER_ERROR
```

用户界面不要直接显示内部 Exception Stack。

---

# 17. 日志

日志目标：
- 能排查；
- 不泄密。

包含：
- 时间
- Level
- Component
- EventId
- TransferId
- DeviceId 的短 ID

禁止：
- API Key
- Authorization Header
- 长期 Device Token
- Pairing Token
- 用户题目 OCR 全文
- AI Prompt 中可能包含的隐私内容（默认不记）

---

# 18. 安全基线

MVP 必须做到：

1. Pairing QR 只短时有效。
2. 每台手机拥有独立 Device Token。
3. 用户可在 Windows 端撤销单个设备。
4. 使用 HTTPS。
5. 手机校验 Desktop Certificate Fingerprint。
6. 不信任上传文件名。
7. 文件类型验证。
8. 文件大小限制。
9. Hash 校验。
10. 无公网监听能力。
11. 不自动打开收到的可执行文件。
12. MVP 只允许图片类型。
13. API Key 系统安全存储。
14. 所有网络输入都做长度和格式校验。
15. QR 数据解析失败不能 crash。

---

# 19. 性能目标

这些是目标，不需要为了极端 benchmark 牺牲简单性。

局域网正常环境：

- 2 MB JPG：
  - 发送至 PC 完成目标 < 2 秒；
- Desktop idle：
  - CPU 应接近空闲；
- 不使用高频轮询；
- mDNS 使用事件驱动；
- 图片缩略图异步生成；
- UI 不因上传和 hash 计算卡顿。

---

# 20. Phase Plan

---

# Phase 0 — Repository Bootstrap

## 目标

建立可编译、可测试、可扩展的最小工程。

## 实现

1. 初始化 Desktop solution。
2. 建立：
   - Desktop
   - Core
   - Transport
   - Infrastructure
   - AI
   - Tests
3. 初始化 Android 工程。
4. 建立基础 CI-friendly build scripts。
5. 建立：
   - README.md
   - PROJECT_STATUS.md
   - docs/PROTOCOL.md
   - docs/SECURITY.md
   - docs/TESTING.md
6. Desktop 显示一个最小主窗口。
7. Android 显示一个最小首页。
8. Core 不引用 UI。

## 验收

必须：
- Desktop build 成功；
- Desktop test 成功；
- Android debug build 成功；
- 无高等级 warning 被忽略；
- 两端都能启动到主界面。

## 完成后

停止。

更新 `PROJECT_STATUS.md`。

不要进入 Phase 1，除非 Phase 0 完全通过。

---

# Phase 1 — Windows Local Receiver

## 目标

先让电脑真正具备“接收图片”的能力。

## 实现

1. Desktop 启动 Local HTTPS Receiver。
2. 建立本机 DeviceIdentity。
3. 建立 SQLite。
4. 实现：
   - `/v1/health`
   - 测试用 authenticated upload
5. 实现 Transfer Service。
6. 文件：
   - temp write
   - size check
   - MIME check
   - SHA-256
   - atomic move
7. 写入 TransferRecord。
8. Desktop Latest / Recent 更新。
9. 建立 protocol smoke test 工具。

Phase 1 可以使用开发期测试 Token，不做最终二维码配对。

## 验收

从同一电脑的 smoke test：

```text
send sample.jpg
```

Desktop 必须：
- 收到；
- 文件落盘；
- hash 一致；
- UI 显示；
- Recent 有记录；
- 重启应用后历史仍存在；
- 非法 MIME 被拒绝；
- >25 MB 被拒绝；
- 路径穿越文件名不会影响真实路径。

## 完成后

停止并报告。

---

# Phase 2 — Secure QR Pairing + Discovery

## 目标

Android 手机首次扫码配对，之后能重新发现 Windows PC。

## Windows

1. 生成/持久化 Desktop TLS identity。
2. Pairing Session。
3. QR Payload。
4. `/v1/pair`。
5. 每设备 token。
6. Device list。
7. Revoke Device。
8. mDNS advertise。

## Android

1. DeviceIdentity。
2. QR scan。
3. fingerprint pinning。
4. Pair API。
5. token secure store。
6. NSD discovery。
7. Online / Offline UI。

## 验收

真实手机 + Windows：

1. PC 点 Add Phone。
2. Android 扫码。
3. 5 秒内显示已连接。
4. 杀掉 Android App 重开。
5. 无需重新扫码即可发现同一电脑。
6. PC 撤销手机。
7. 手机下一次请求必须失败并显示需要重新配对。
8. 修改错误 fingerprint 时连接必须被拒绝。

## 完成后

停止并报告。

---

# Phase 3 — Camera → PC

## 目标

完成项目第一个真正有价值的闭环。

```text
手机拍照 → 点击发送 → PC 出现
```

## Android

1. CameraX preview。
2. 拍照。
3. EXIF orientation。
4. Preview。
5. Retake。
6. Send。
7. Upload progress。
8. Retry。
9. 最近一次连接 PC 默认选中。

## Windows

1. 接收真实 Android 上传。
2. Latest Image。
3. Thumbnail。
4. Recent History。
5. Open。
6. Copy Image。
7. Open Folder。

## 验收

真实使用：

1. 手机对一道题拍照。
2. 点击 Send。
3. 电脑无需其他动作。
4. 图片自动出现。
5. 图片方向正确。
6. 画质足以放大阅读题目。
7. 网络中断时手机不会假装成功。
8. 恢复网络后可重试。
9. 重复点击不能产生失控重复上传。

**这是第一个可日常使用版本。**

完成后停止。

---

# Phase 4 — Crop / Scan UX

## 目标

减少“手动裁题”的时间。

## 实现顺序

### 4A 手动 Crop
先实现稳定的：
- 4-corner crop；
- rotation；
- preview；
- reset to original。

### 4B Auto Document Detection
再实现：
- 最大有效四边形检测；
- 透视校正；
- 自动 preview；
- 手动调整 fallback。

## 验收

至少测试：
- 白纸放在桌面；
- 斜拍；
- 屏幕拍摄；
- 光线较暗；
- 没有明显纸张边界。

自动识别失败时：
- App 不 crash；
- 仍能原图发送；
- 用户能手动 crop。

完成后停止。

---

# Phase 5 — AI Vision

## 目标

把“收到图片”升级成“收到题目后立即解题”。

## 实现

Windows Settings：

```text
AI Provider
Base URL
API Key
Model
Test Connection
```

Latest card：

```text
[Ask AI]
```

点击：

```text
Question Image
 ↓
IAiVisionProvider
 ↓
Vision API
 ↓
Answer
```

UI：

```text
Thinking...
```

然后显示 Markdown 解析。

## 验收

1. 用户填写真实 API Key。
2. Test Connection 成功。
3. 手机拍一道题发送。
4. Desktop 收到。
5. 点击 Ask AI。
6. 正确发送图片。
7. 返回答案。
8. Markdown 正常显示。
9. 超时能取消。
10. 错误 API Key 有明确错误。
11. API Key 不出现在 log 和 db 明文中。

完成后停止。

---

# Phase 6 — One-Tap Study Flow

## 目标

进一步减少操作。

Android 发送按钮增加：

```text
Send to PC
Send & Ask AI
```

如果选择 `Send & Ask AI`：

```text
Phone Capture
 ↓
Upload purpose=Question
 ↓
PC receive
 ↓
Auto start AI
 ↓
Show Answer
```

Windows 必须提供设置：

```text
[ ] Automatically ask AI for "Question" transfers
```

默认关闭，用户主动开启。

## 验收

开启后：

1. 手机拍题。
2. 点 Send & Ask AI。
3. 不操作电脑。
4. PC 自动收到图片。
5. AI 自动开始。
6. 最后答案出现。

这个阶段完成后，核心产品体验成立。

---

# 21. Post-MVP Backlog

以下只能在 Phase 0–6 稳定后开始。

优先级建议：

## P1
- 一页多题自动识别
- OCR
- Question Block Selection
- 连续刷题 Session
- AI 历史

## P2
- 错题收藏
- 科目分类
- AI 知识点标签
- 复习统计

## P3
- Clipboard Sync
- PC → Phone
- 文件互传
- Drag & Drop Send
- Windows Explorer Send To

## P4
- iOS
- macOS
- 云端中继
- 外网传输

---

# 22. 当前明确不做

Coding Agent 如果在 MVP 中主动实现以下内容，视为范围失控：

```text
账号登录
服务器后台
云存储
微信集成
QQ 集成
全相册同步
通讯录
聊天
社交
复杂 NAS 功能
Web 控制台
插件市场
多人共享
端到端云同步
OCR 知识图谱
完整错题数据库
自动生成学习计划
```

---

# 23. UX 原则

所有 UI 决策遵守：

## 23.1 手机

用户进入 App 的第一需求不是“看文件”。

而是：

**拍。**

所以 Camera 必须是主入口。

---

## 23.2 电脑

电脑收到的不是：

> IMG_20260815_140822.jpg

而是：

> 刚从手机收到的内容。

优先展示内容本身。

---

## 23.3 低打扰

- 不强抢焦点；
- 不让窗口乱跳；
- 不播放刺耳声音；
- 不频繁弹 Toast；
- 不因失败静默；
- 收到内容时轻量提示即可。

---

## 23.4 操作数

对已配对设备：

目标路径：

```text
Open App
Capture
Send
```

最多 3 个核心动作。

Smart Crop 稳定后：

```text
Open App
Capture
Send
```

不强制进入编辑页。

未来可提供：

```text
Capture
```

后自动发送，但必须由用户主动开启。

---

# 24. 测试矩阵

每个相关 Phase 要覆盖。

## Network

```text
Same Wi-Fi
PC changes IP
Phone changes IP
Wi-Fi disconnect
Upload interrupted
PC app restart
Phone app restart
```

## Image

```text
Portrait
Landscape
Large JPG
PNG
Dark photo
Rotated EXIF
Invalid image
Fake MIME
```

## Pairing

```text
Valid QR
Expired QR
Reused QR
Wrong fingerprint
Revoked device
Unknown device
Malformed QR
```

## Storage

```text
Disk unavailable
Read-only failure
Temp cleanup
Database restart persistence
Duplicate transfer id
```

---

# 25. Definition of Done

一个 Phase 只有满足全部条件才能写“完成”。

```text
[ ] Code implemented
[ ] Build passes
[ ] Tests pass
[ ] Manual smoke test passes
[ ] Errors handled
[ ] Logs usable
[ ] No secret leakage
[ ] No obvious TODO placeholder in core path
[ ] Docs updated
[ ] PROJECT_STATUS.md updated
```

---

# 26. PROJECT_STATUS.md 格式

Agent 第一次执行必须创建：

```markdown
# Phone-Link Project Status

## Current Phase
Phase 0

## Status
IN_PROGRESS

## Last Verified Commit
<hash>

## Completed
- ...

## Verification
- Desktop build:
- Desktop tests:
- Android build:
- Manual test:

## Known Issues
- ...

## Next Action
- ...
```

每次结束时更新。

---

# 27. Git 工作纪律

如果当前目录已经是 Git 仓库：

1. 先检查 `git status`。
2. 不覆盖用户未提交修改。
3. 不执行 destructive reset。
4. 不删除无法确认用途的文件。
5. 每阶段最好形成独立 commit。
6. Commit message：

```text
phase-0: bootstrap phone-link workspace
phase-1: add local image receiver
phase-2: add secure device pairing
phase-3: complete camera-to-pc flow
phase-4: add crop and scan pipeline
phase-5: add ai vision bridge
phase-6: add one-tap study flow
```

如果 Agent 无提交权限，则在状态文件中给出建议 commit。

---

# 28. Agent 开工指令

读取到这里以后，不需要再设计产品。

现在执行：

## 第一步

检查仓库：

```text
files
git status
existing solution/project
available SDKs
```

## 第二步

判断：
- 空仓库 → 初始化 Phase 0；
- 已有 Phone-Link 代码 → 对照 Phase 0 做 gap analysis；
- 有无关项目 → 不修改无关内容。

## 第三步

执行 **Phase 0 only**。

## 第四步

完成 Phase 0 全部验收。

## 第五步

更新：

```text
PROJECT_STATUS.md
README.md
docs/*
```

## 第六步

停止，并向用户报告：

```text
Phase 0 status
Files changed
Build results
Test results
Manual verification
Known issues
Recommended next phase
```

**不要自动进入 Phase 1，除非用户明确要求继续。**

---

# 29. 产品北极星

在任何实现决策冲突时，以这句话决定优先级：

> **让手机拍到的东西，在电脑上零摩擦地继续工作。**

对于当前第一使用场景，更具体地说：

> **用户看到不会的题，拿手机拍一下，电脑端立刻得到一张干净、可直接交给 AI 的题目图片。**

如果一个功能不能明显缩短这条路径，就不是当前优先级。

---

# 30. MVP 最终体验

Phase 6 完成后，目标体验应为：

```text
用户复习
   ↓
遇到不会的题
   ↓
拿起手机
   ↓
打开 Phone-Link
   ↓
对准题目拍照
   ↓
自动/快速裁切
   ↓
Send & Ask AI
   ↓
手机放下
   ↓
Windows 自动收到
   ↓
AI Thinking
   ↓
电脑显示答案解析
```

整个过程中不需要：

```text
打开相册
保存编辑后的副本
打开微信
打开文件传输助手
等待聊天同步
电脑下载图片
拖图片
重新输入固定 Prompt
```

这就是 Phone-Link MVP 的完成标准。
