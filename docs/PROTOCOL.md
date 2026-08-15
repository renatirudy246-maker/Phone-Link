# Phone-Link Protocol (v1)

局域网、HTTPS、REST、mDNS 发现、QR 首次配对。当前已实现：Phase 1（本地接收）；配对/发现随 Phase 2 实现后更新本文。

## 概览

| 项 | 值 |
| --- | --- |
| 传输 | 局域网 HTTPS（自签证书，证书 SHA-256 fingerprint 用于 Phase 2 钉扎） |
| 发现 | mDNS `_phonelink._tcp.local`（Phase 2） |
| 配对 | 一次性 QR + 证书指纹钉扎（Phase 2） |
| 鉴权 | Phase 1 开发期：共享 Dev Token（`%LOCALAPPDATA%\PhoneLink\data\dev-token.txt`，仅用于联调，Phase 2 替换为每设备 Device Token） |
| API 前缀 | `/v1` |
| 默认端口 | 8484 |

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

## 错误模型

统一返回（HTTP 状态码见下表）：

```json
{ "code": "TRANSFER_HASH_MISMATCH", "message": "...", "retryable": true }
```

| code | HTTP |
| --- | --- |
| AUTH_INVALID | 401 |
| DEVICE_REVOKED / PAIR_TOKEN_EXPIRED / PAIR_ALREADY_USED | 403 |
| FILE_TOO_LARGE | 413 |
| UNSUPPORTED_MEDIA_TYPE | 415 |
| TRANSFER_HASH_MISMATCH | 422 |
| DISK_WRITE_FAILED | 500 |
| NOT_FOUND | 404 |
| 其他（INVALID_REQUEST 等） | 400 |

## 待 Phase 2 补充

- `POST /v1/pair` 请求/响应
- QR Payload 格式与 fingerprint 校验流程
- mDNS TXT 记录内容