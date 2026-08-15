# Security Baseline

MVP 安全要求，按 Phase 逐项落实。

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
| 4 | HTTPS | ✅ Phase 1（Kestrel + 自签证书） |
| 5 | 手机校验 Desktop 证书指纹 | Phase 2 |
| 6 | 不信任上传文件名（服务端生成文件名，防 `../`） | ✅ Phase 1 |
| 7 | MIME 白名单 + 文件头校验 | ✅ Phase 1 |
| 8 | 单文件 ≤ 25 MB | ✅ Phase 1 |
| 9 | SHA-256 完整性校验 | ✅ Phase 1 |
| 10 | 无公网监听 | ✅ Phase 1（仅绑定本机网卡，防火墙未自动开洞） |
| 11 | 不自动打开收到的可执行文件（MVP 仅图片） | ✅ Phase 1（仅接受 image/jpeg、png、webp） |
| 12 | API Key 系统安全存储 | Phase 5 |
| 13 | 网络输入长度/格式校验 | ✅ Phase 1（transferId/sha/文件名长度与格式校验、metadata ≤16KB） |
| 14 | QR 解析失败不 crash | Phase 2 |

## 本地存储

```
%LOCALAPPDATA%\PhoneLink\
├─ data\
│  ├─ phonelink.db      元数据（无裸 token；Dev Token 单列于独立文件）
│  └─ dev-token.txt     Phase 1 开发期测试 Token（256-bit 随机，Phase 2 移除）
├─ inbox\YYYY-MM-DD\    <transferId>.<ext>（文件名由服务端生成）
├─ thumbnails\
├─ logs\
└─ temp\                上传中间文件，成功后原子移入 inbox，失败即清理
```

上传流程：temp 写入 → 大小/MIME 文件头/hash 校验 → 原子移动 → SQLite 记录。

## Phase 1 已知的临时项（Phase 2 收口）

- `dev-token.txt` 以明文存放开发期 Token（仅本机、仅联调期，明确标记为 Phase 2 移除项）。
- Desktop TLS 证书存放于当前用户证书库（Windows DPAPI 保护），Phase 2 使用其 SHA-256 fingerprint 做客户端钉扎。