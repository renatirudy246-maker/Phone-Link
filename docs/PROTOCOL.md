# Phone-Link Protocol (v1)

局域网、HTTPS、REST、mDNS 发现、QR 首次配对。本文件随 Phase 演进更新。

## 概览

| 项 | 值 |
| --- | --- |
| 传输 | 局域网 HTTPS（自签证书） |
| 发现 | mDNS `_phonelink._tcp.local` |
| 配对 | 一次性 QR + 证书指纹钉扎 |
| 鉴权 | Bearer Device Token（每设备独立，≥256-bit） |
| API 前缀 | `/v1` |

## 端点

- `GET /v1/health` — 预配对仅返回协议版本；配对后需鉴权。
- `POST /v1/pair` — 首次配对，换取长期 device token。
- `POST /v1/transfers` — multipart 上传（metadata=JSON + file=二进制）。
- `GET /v1/transfers/{transferId}` — 上传后状态确认（重试去重）。

## QR Payload

```
phonelink://pair? v=1 & deviceId=... & name=... & host=... & port=... & token=... & fp=...
```

`fp` = Desktop TLS 证书 SHA-256 fingerprint，手机必须校验。

## 传输完整性

1. 手机计算 SHA-256，写入 metadata。
2. PC 写入 temp 后重新计算并比对。
3. 不一致：删除 temp、标记失败、返回 `TRANSFER_HASH_MISMATCH`。
4. 结果不确定时客户端调用 `GET /v1/transfers/{id}` 确认，避免重复上传。

## 错误模型

统一返回：

```json
{ "code": "TRANSFER_HASH_MISMATCH", "message": "...", "retryable": true }
```

错误码全集见 `PhoneLink.Core.Errors.ErrorCodes`（§16 规范）。

## 状态（占位，Phase 1 填充）

- 本文件当前为协议骨架；具体请求/响应示例随 Phase 1–2 实现后补充并保持与实现一致。