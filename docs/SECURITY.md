# Security Baseline

MVP 安全要求（实现于 Phase 1–2 起逐项落实并验证）。

## 原则

- 服务只监听局域网，不暴露公网（禁止 UPnP 自动开端口、禁止中央中继）。
- 一切敏感信息使用系统安全能力保护：Windows DPAPI / Credential Manager，Android Keystore。
- 日志/数据库禁止出现：API Key、Authorization Header、长期 Device Token、Pairing Token。

## 清单

| # | 要求 | 状态 |
| --- | --- | --- |
| 1 | Pairing QR 短时有效 | Phase 2 |
| 2 | 每台手机独立 Device Token | Phase 2 |
| 3 | Windows 端可撤销单个设备 | Phase 2 |
| 4 | HTTPS | Phase 1 |
| 5 | 手机校验 Desktop 证书指纹 | Phase 2 |
| 6 | 不信任上传文件名（服务端生成文件名，防 `../`） | Phase 1 |
| 7 | MIME 白名单 + 文件头校验 | Phase 1 |
| 8 | 单文件 ≤ 25 MB | Phase 1 |
| 9 | SHA-256 完整性校验 | Phase 1 |
| 10 | 无公网监听 | Phase 1 |
| 11 | 不自动打开收到的可执行文件（MVP 仅图片） | Phase 1 |
| 12 | API Key 系统安全存储 | Phase 5 |
| 13 | 网络输入长度/格式校验 | Phase 1 |
| 14 | QR 解析失败不 crash | Phase 2 |

## 本地存储（Phase 1 起）

```
%LOCALAPPDATA%\PhoneLink\
├─ data\phonelink.db      元数据（不含裸 token / API Key）
├─ inbox\YYYY-MM-DD\      <transfer-id>.<ext>
├─ thumbnails\
├─ logs\
└─ temp\
```

上传流程：temp 写入 → 大小/MIME/hash 校验 → 原子移动到 inbox。