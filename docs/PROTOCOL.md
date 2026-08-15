# Phone-Link Protocol (v1)

局域网、HTTPS、REST、mDNS 发现、QR 首次配对。已实现 Phase 1（本地接收）+ Phase 2（QR 配对 + 指纹钉扎 + mDNS 发现）+ Phase 3（手机拍照/相册上传 + 暂停接收）。

## 概览

| 项 | 值 |
| --- | --- |
| 传输 | 局域网 HTTPS（自签证书，证书 SHA-256 fingerprint 用于客户端钉扎） |
| 发现 | mDNS `_phonelink._tcp.local`（Windows 内建 DNS-SD，dnsapi.dll） |
| 配对 | 一次性 QR + 证书指纹钉扎（TrustManager 校验 DER SHA-256） |
| 鉴权 | 每设备 Device Token（`Authorization: Bearer <token>`） |
| API 前缀 | `/v1` |
| 默认端口 | 8484 |

## 发现（mDNS）

服务类型 `_phonelink._tcp.local`，实例名 `{DeviceName}._phonelink._tcp.local`，端口 8484。

TXT 记录仅含非敏感元数据：

| key | 值 |
| --- | --- |
| `version` | 协议版本（1） |
| `deviceId` | 桌面端 ID（`desktop-<guid>`） |
| `name` | 桌面端名称 |

mDNS 不可用（Windows 版本过旧）时桌面端静默降级：配对仍可通过 QR 完成，仅自动发现不可用。手机端发现超时后回退到已保存的 host/port。

## 配对（QR）

### QR Payload

compact JSON 序列化后 Base64URL 编码（无 padding），UTF-8。字段名固定（PascalCase，与 Windows `PairingQrCodec` 契约一致）：

```json
{
  "ProtocolVersion": 1,
  "DesktopDeviceId": "desktop-...",
  "DesktopDeviceName": "DESKTOP-XXX",
  "Host": "192.168.5.28",
  "Port": 8484,
  "OneTimeToken": "<256-bit random, base64url>",
  "CertificateFingerprint": "AA:BB:...（证书 DER SHA-256，Windows 格式）",
  "ExpiresAt": "2026-08-15T12:00:00.0000000+08:00"
}
```

- 长度上限 2048 字符。
- `ExpiresAt` 为 ISO-8601 带时区（Windows 7 位小数秒；Android 用 `OffsetDateTime.parse` 解析）。
- 手机端解码后校验字段非空、端口合法、未过期、协议版本匹配。
- OneTimeToken 一次性：配对成功或失败后立即作废；过期（默认 3 分钟）后不可用。

### POST /v1/pair

请求（lowercase JSON，无鉴权头）：

```json
{
  "oneTimeToken": "...",
  "mobileDeviceId": "mobile-<uuid 无连字符>",
  "mobileDeviceName": "MEIZU 21",
  "platform": "android",
  "protocolVersion": 1
}
```

成功（200）：

```json
{ "deviceToken": "<长期设备 token>", "desktopDeviceId": "desktop-...", "protocolVersion": 1 }
```

失败：

| code | HTTP | 说明 |
| --- | --- | --- |
| PAIR_TOKEN_INVALID | 401 | token 不存在/已作废/未知设备 |
| PAIR_TOKEN_EXPIRED | 403 | 已过期 |
| PAIR_ALREADY_USED | 403 | 已被使用（一次性） |
| DEVICE_REVOKED | 403 | 设备已被桌面端撤销 |
| AUTH_INVALID | 401 | 其他鉴权失败 |

响应 `{ "code": "...", "message": "...", "retryable": true|false }`。手机端对 403 类错误展示明确文案（"过期/已使用/已撤销"），不重试；网络错误可重试。

## 端点（已实现）

### GET /v1/health

无 token → 仅返回协议版本（预配对最小响应）：

```json
{ "protocolVersion": 1 }
```

带有效 `Authorization: Bearer <token>` → 完整信息：

```json
{ "protocolVersion": 1, "deviceId": "desktop-...", "deviceName": "...", "status": "ok" }
```

带**无效/被撤销设备**的 token → 401/403（`AUTH_INVALID`/`DEVICE_REVOKED`），手机端据此触发"重新配对"或"已撤销"提示。

### POST /v1/transfers

`multipart/form-data`，两个 part，**metadata 必须位于 file 之前**：

```text
metadata = JSON（≤16KB）
file     = 二进制图片流
```

metadata 字段：

```json
{
  "transferId": "t-...（≤64，[A-Za-z0-9-_]）",
  "senderDeviceId": "mobile-...（≤128）",
  "originalFileName": "question.jpg（仅用于展示，服务端落盘文件名由服务端生成）",
  "mimeType": "image/jpeg | image/png | image/webp",
  "fileSize": 123456,
  "width": 3000,
  "height": 4000,
  "sha256": "<64 hex>",
  "capturedAt": "2026-08-15T10:00:00+08:00",
  "sentAt": "2026-08-15T10:00:01+08:00",
  "purpose": "Question | Photo | File"
}
```

成功（200）：

```json
{ "transferId": "...", "status": "completed", "receivedAt": "..." }
```

服务端处理管线：temp 写入（流式计数，>25MB 中止）→ MIME 文件头校验 → SHA-256 校验 → 原子移动到 `inbox\yyyy-MM-dd\<transferId>.<ext>` → SQLite 记录 → 事件通知 UI。

幂等：同一 `transferId` 已 Completed 时直接返回既有记录，不重复落盘。

### GET /v1/transfers/{transferId}

```json
{ "transferId": "...", "status": "completed", "receivedAt": "...", "errorCode": null, "localFilePath": "..." }
```

未知 id → 404。

## 传输完整性

1. 手机/客户端计算 SHA-256，写入 metadata。
2. PC 写入 temp 时同步计算，落盘后比对。
3. 不一致：删除 temp、标记失败、返回 `TRANSFER_HASH_MISMATCH`（422）。
4. 结果不确定时客户端调用 `GET /v1/transfers/{id}` 确认，避免重复上传。

## 客户端发送流程（Phase 3）

1. **图片预处理**：读取 EXIF orientation → 旋转为实际像素方向（输出 JPEG 不再依赖 EXIF）→ JPEG quality 95、最长边 ≤4096（可读性优先）→ 流式计算 SHA-256。
2. **multipart 上传**：metadata 先于 file，64KB 分块流式写入，进度按真实字节累计。
3. **幂等重试**：重试复用同一 `transferId`；若上次请求结果不确定（超时/连接中断），先 `GET /v1/transfers/{id}`——已 Completed 则本地标成功不再上传，404/Failed 才重新上传同 ID。
4. **错误分类**：403 `DEVICE_REVOKED`（不可重试，提示重新配对）、503 `SERVICE_PAUSED`（可重试，提示桌面端已暂停）、TLS 指纹不匹配（不可重试，提示重新配对）、网络错误（可重试）。
5. **发送方向**：拍照方向由重力传感器驱动 `ImageCapture.targetRotation`（竖拿竖拍、横拿横拍），与系统"自动旋转"设置无关；Activity 锁定竖屏。

## 错误模型

统一返回（HTTP 状态码见下表）：

```json
{ "code": "TRANSFER_HASH_MISMATCH", "message": "...", "retryable": true }
```

| code | HTTP |
| --- | --- |
| AUTH_INVALID | 401 |
| PAIR_TOKEN_INVALID | 401 |
| DEVICE_REVOKED / PAIR_TOKEN_EXPIRED / PAIR_ALREADY_USED | 403 |
| FILE_TOO_LARGE | 413 |
| UNSUPPORTED_MEDIA_TYPE | 415 |
| TRANSFER_HASH_MISMATCH | 422 |
| SERVICE_PAUSED | 503 |
| DISK_WRITE_FAILED | 500 |
| NOT_FOUND | 404 |
| 其他（INVALID_REQUEST 等） | 400 |

`SERVICE_PAUSED`（503）：桌面端托盘"暂停接收"开启时，已鉴权的上传请求返回该错误（幂等 GET 状态查询不受影响）；手机端提示"桌面端已暂停接收"，标记为可重试。

## 证书与指纹

- 桌面端首次运行生成自签 RSA-2048 证书（CN=PhoneLink-Desktop），持久化到当前用户证书库（DPAPI 保护），重启复用同一证书。
- 指纹 = 证书 DER 编码的 SHA-256，冒号分隔十六进制（Windows `Convert.ToHexString` 格式）。
- 手机端用自定义 TrustManager 校验：握手时计算服务器证书 DER SHA-256 与 QR 中的指纹比对，不一致抛 `SSLHandshakeException`（提示"证书指纹不匹配"），绝不放行。hostname 校验禁用（自签证书 CN 是机器名，IP 直连无意义）。
- 桌面端证书更换后（用户手动删除/轮换），手机端重连会因指纹不匹配被拒绝，需重新扫码配对。