# Testing

## 命令

```powershell
# Desktop build + 全部 .NET 测试
.\tools\build-desktop.ps1

# Android debug 构建 + JVM 单元测试
.\tools\build-android.ps1

# 端到端冒烟测试（启动真实 Desktop 应用 → 本机上传 → 重启验证持久化）
.\tools\run-smoke-test.ps1

# Android 单元测试（在 src/android/PhoneLinkAndroid 下）
.\gradlew.bat :app:testDebugUnitTest

# 桌面端配对/QR 工具（验收用）
dotnet run --project tools/qr-show -- show <payloadFile> <pngOut>      # payload → QR PNG
dotnet run --project tools/qr-show -- verify <payloadFile> <pngPath>   # 回读验证
dotnet run --project tools/qr-show -- devices <dataDir>                # 列出已配对设备
dotnet run --project tools/qr-show -- revoke <dataDir> <deviceId>      # 撤销设备
dotnet run --project tools/qr-show -- restore <dataDir> <deviceId>     # 恢复设备
dotnet run --project tools/qr-show -- reset-cert                       # 删除桌面证书（轮换测试）
```

## 分层

- **单元测试**（`PhoneLink.Core.Tests` / `PhoneLink.Transport.Tests`）：模型、错误码、Bearer 解析、metadata 解析、错误映射、证书指纹、QR payload 编解码。
- **集成测试**（`PhoneLink.IntegrationTests`）：进程内真实 Kestrel HTTPS + 真实 SQLite + 真实文件管道，覆盖上传管线、配对会话生命周期、撤销、token 校验。
- **Android JVM 单测**（`app/src/test`）：QR payload 编解码（Base64URL/JSON/过期/畸形）、指纹规范化与 pin 匹配。
- **协议冒烟**（`tools/protocol-smoke-test`）：对运行中的真实 Desktop 应用做端到端验证。
- **实机验收**（MEIZU 21 + 真实 Wi-Fi）：扫码配对、自动重连、撤销、指纹拒绝。

## 当前覆盖（103 项自动化测试 = Desktop 93 + Android 10，冒烟 14 场景）

### HTTP / 上传（IntegrationTests）
- health：无 token 最小响应 / 有效 token 完整信息 / 无效 token 401（含被撤销设备）
- JPEG、PNG 上传成功：落盘位置、SHA-256 一致、DB 记录 Completed
- 伪造 MIME（PNG 字节声明 jpeg）→ 415
- 未知 MIME → 415
- >25MB → 413 且 temp 清理
- hash mismatch → 422 且 temp 清理
- 路径穿越文件名（`../../../evil.jpg`）→ 安全落盘、文件名仅取 basename
- 重复 transferId → 幂等（单一文件、单一记录）
- 无效/缺失 token → 401
- 缺 file part / 坏 metadata JSON → 400
- GET 未知 transfer → 404
- 成功上传发布事件（供 UI 实时更新）
- 重启（同数据目录新 host）→ 记录、文件、HTTP 历史均保留
- 写盘失败（temp 目录被文件占用）→ DISK_WRITE_FAILED

### 配对/安全（IntegrationTests + Core.Tests）
- POST /v1/pair：有效 token 换发 Device Token / 过期 token 403 / 已用 token 403 / 撤销设备 403
- 设备 token：未知 token 401、撤销后 401/403、token 以 SHA-256 哈希存储（无裸 token）
- 配对会话清理：过期会话被回收、一次性 token 不可复用
- health 区分"无 token 预配对响应"与"带无效 token 拒绝"

### 解析/映射（Transport.Tests）
- Bearer token 提取（大小写、空值、其他 scheme）
- metadata 校验：缺字段、非法 id 字符、坏 sha、非法 purpose、负 width、路径穿越/Windows 路径文件名剥离、时间戳缺省、坏 JSON
- 错误码 → HTTP 状态码映射

### Android 单测（JVM）
- QrPayloadCodecTest（6）：Base64URL 解码、PascalCase 字段、过期拒绝、畸形 Base64/JSON/缺字段拒绝、长度上限
- FingerprintsTest（4）：`AA:BB` 规范化为 hex、pin 匹配/不匹配、大小写不敏感

### 冒烟（真实应用，14 场景）
- health × 2、JPEG/PNG 上传 + 落盘 + SHA-256、状态查询、404、伪造 MIME、>25MB、hash mismatch、路径穿越、幂等、401、重启后历史保留

### 实机验收（MEIZU 21，2026-08-15）

| 场景 | 结果 |
| --- | --- |
| 扫码配对（QR → TLS 指纹钉扎 → /v1/pair） | ✅ 成功，设备持久化 trusted=True |
| 重启 app 自动重连（NSD 发现 + 指纹 + token 校验） | ✅ 自动连接，UI 显示"已连接" |
| 桌面端撤销设备 | ✅ 手机 health 返回 DEVICE_REVOKED，UI 提示"已被撤销，请重新扫码配对" |
| 桌面证书更换后重连 | ✅ SSLHandshakeException（fingerprint mismatch），UI 明确提示"指纹不一致"，拒绝连接 |

## 测试矩阵

| 类别 | 场景 | 状态 |
| --- | --- | --- |
| Network | 同一 Wi-Fi / PC 换 IP / 手机换 IP / Wi-Fi 断连 / 上传中断 / 两端重启 | 重启已自动化；同 Wi-Fi + 自动重连已实机验证；断连/换 IP 随 Phase 3 |
| Image | 竖拍 / 横拍 / 大 JPG / PNG / 暗光 / EXIF 旋转 / 非法图片 / 伪造 MIME | 大 JPG/PNG/伪造 MIME 已覆盖；EXIF 等随 Phase 3 |
| Pairing | 有效 QR / 过期 QR / 复用 QR / 错误指纹 / 撤销设备 / 未知设备 / 畸形 QR | ✅ 有效、过期、复用、错误指纹、撤销、畸形（单测）均已覆盖（过期/复用/未知设备为自动化测试） |
| Storage | 磁盘不可用 / 只读失败 / temp 清理 / 重启持久化 / 重复 transferId | 已覆盖（temp 清理、重启持久化、重复 id、写盘失败） |