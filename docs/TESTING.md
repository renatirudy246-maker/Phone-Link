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
- **集成测试**（`PhoneLink.IntegrationTests`）：进程内真实 Kestrel HTTPS + 真实 SQLite + 真实文件管道，覆盖上传管线、配对会话生命周期、撤销、token 校验、暂停接收。
- **WPF 单元测试**（`PhoneLink.Desktop.Tests`）：Latest 操作（图片路径校验、explorer 参数构造、剪贴板复制失败路径）。
- **Android JVM 单测**（`app/src/test`）：QR payload 编解码、指纹规范化与 pin 匹配、TransferManifest 序列化、传输错误分类、流式 SHA-256。
- **协议冒烟**（`tools/protocol-smoke-test`）：对运行中的真实 Desktop 应用做端到端验证。
- **实机验收**（MEIZU 21 + 真实 Wi-Fi）：拍照/相册发送、方向、复制/打开、断网重试幂等、重启、撤销、暂停接收。

## 当前覆盖（130 项自动化测试 = Desktop 103 + Android 27，冒烟 14 场景）

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
- 暂停接收（PauseTests，3）：暂停时上传 503 SERVICE_PAUSED、health 仍可用、恢复后上传成功

### 配对/安全（IntegrationTests + Core.Tests）
- POST /v1/pair：有效 token 换发 Device Token / 过期 token 403 / 已用 token 403 / 撤销设备 403
- 设备 token：未知 token 401、撤销后 401/403、token 以 SHA-256 哈希存储（无裸 token）
- 配对会话清理：过期会话被回收、一次性 token 不可复用
- health 区分"无 token 预配对响应"与"带无效 token 拒绝"

### 解析/映射（Transport.Tests）
- Bearer token 提取（大小写、空值、其他 scheme）
- metadata 校验：缺字段、非法 id 字符、坏 sha、非法 purpose、负 width、路径穿越/Windows 路径文件名剥离、时间戳缺省、坏 JSON
- 错误码 → HTTP 状态码映射（含 SERVICE_PAUSED → 503）

### WPF（Desktop.Tests，7）
- IsValidImagePath：合法 jpg/png/webp / 目录 / 不存在 / 其他扩展名
- BuildExplorerSelectArgument：含空格路径用引号包裹 / 多级子目录 / 普通路径
- CopyImage：目标文件缺失返回 false（不弹异常）

### Android 单测（JVM）
- QrPayloadCodecTest（6）：Base64URL 解码、PascalCase 字段、过期拒绝、畸形 Base64/JSON/缺字段拒绝、长度上限
- FingerprintsTest（4）：`AA:BB` 规范化为 hex、pin 匹配/不匹配、大小写不敏感
- TransferManifestTest（3）：toJson 全小写字段、TransferId 唯一性/前缀/长度、SenderDeviceId 唯一性
- TransferErrorClassifierTest（11）：403 revoked、401 auth、503 SERVICE_PAUSED（可重试）、413、415、422 hash mismatch（可重试）、500、TLS 指纹（不可重试）、连接拒绝/超时（可重试）、通用 IO
- ImagePreparerSha256Test（2）：流式 SHA-256 与参考摘要一致（小文件 + 跨 64KB 分块的大文件）

### 冒烟（真实应用，14 场景）
- health × 2、JPEG/PNG 上传 + 落盘 + SHA-256、状态查询、404、伪造 MIME、>25MB、hash mismatch、路径穿越、幂等、401、重启后历史保留

### 实机验收（MEIZU 21，2026-08-15）

Phase 2：

| 场景 | 结果 |
| --- | --- |
| 扫码配对（QR → TLS 指纹钉扎 → /v1/pair） | ✅ 成功，设备持久化 trusted=True |
| 重启 app 自动重连（NSD 发现 + 指纹 + token 校验） | ✅ 自动连接，UI 显示"已连接" |
| 桌面端撤销设备 | ✅ 手机 health 返回 DEVICE_REVOKED，UI 提示"已被撤销，请重新扫码配对" |
| 桌面证书更换后重连 | ✅ SSLHandshakeException（fingerprint mismatch），UI 明确提示"指纹不一致"，拒绝连接 |

Phase 3（TEST A–H）：

| 场景 | 结果 |
| --- | --- |
| A：拍照 → 预览 → 发送 → PC 自动显示 | ✅ Latest 自动更新，3.1MB 原图秒级完成 |
| B：方向（系统自动旋转关闭）| ✅ 竖拍 3000×4000（EXIF=6 旋转生效）、横拍 4000×3000（EXIF=1）；重力传感器驱动 TargetRotation，不依赖系统设置 |
| C：Windows 端操作 | ✅ 复制图片 → 剪贴板 4000×3000 位图；打开 / 打开所在文件夹（explorer /select）正常 |
| D：相册选图 | ✅ 长图 2292×1690、截图 2292×436 均成功 |
| E：断网重试幂等 | ✅ 断网发送失败 → 恢复后重试成功，PC 端仅一个文件（同 TransferId，无重复） |
| F：桌面端重启 | ✅ Latest 从磁盘恢复，手机无需重新配对 |
| G：撤销设备 | ✅ 发送被拒（"设备已被桌面端撤销"）→ 重新扫码配对恢复 |
| H：托盘暂停接收 | ✅ 暂停时发送失败（503 SERVICE_PAUSED）→ 恢复后成功 |

性能实测（同 Wi-Fi）：

| 数据 | 结果 |
| --- | --- |
| 2.2MB JPEG 上传（手机端计时） | 267ms（目标 <2s 达成） |
| 434KB JPEG 上传（手机端计时） | 121ms |
| 服务端 434KB 请求处理 | 118ms（含落盘） |

## 测试矩阵

| 类别 | 场景 | 状态 |
| --- | --- | --- |
| Network | 同一 Wi-Fi / PC 换 IP / 手机换 IP / Wi-Fi 断连 / 上传中断 / 两端重启 | 重启已自动化 + 实机验证；断网重试幂等已实机验证（TEST E） |
| Image | 竖拍 / 横拍 / 大 JPG / PNG / 暗光 / EXIF 旋转 / 非法图片 / 伪造 MIME | 大 JPG/PNG/伪造 MIME/EXIF 旋转/方向已覆盖；非法图片走 ImagePreparer 失败文案 |
| Pairing | 有效 QR / 过期 QR / 复用 QR / 错误指纹 / 撤销设备 / 未知设备 / 畸形 QR | ✅ 已全部覆盖（单测 + 实机） |
| Transfer | 断网重试 / 幂等 / 暂停接收 / 失败分类 / 进度节流 | ✅ 实机 TEST E/H + 单测 + 集成测试 |
| Storage | 磁盘不可用 / 只读失败 / temp 清理 / 重启持久化 / 重复 transferId | 已覆盖（temp 清理、重启持久化、重复 id、写盘失败） |