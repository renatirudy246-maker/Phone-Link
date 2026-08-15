# Security Baseline

MVP 安全要求，按 Phase 逐项落实。

## 原则

- 服务只监听局域网，不暴露公网（禁止 UPnP 自动开端口、禁止中央中继）。
- 一切敏感信息使用系统安全能力保护：Windows DPAPI / Credential Manager，Android Keystore。
- 日志/数据库禁止出现：API Key、Authorization Header、长期 Device Token、Pairing Token。
- 配对信任锚 = 证书指纹（DER SHA-256），QR 内容可公开但不能被替换。

## 清单

| # | 要求 | 状态 |
| --- | --- | --- |
| 1 | Pairing QR 短时有效 | ✅ Phase 2（3 分钟 TTL，过期/使用后作废） |
| 2 | 每台手机独立 Device Token | ✅ Phase 2（SQLite 持久化，撤销即刻失效） |
| 3 | Windows 端可撤销单个设备 | ✅ Phase 2（工具 `qr-show revoke`，手机 health 返回 DEVICE_REVOKED） |
| 4 | HTTPS | ✅ Phase 1（Kestrel + 自签证书） |
| 5 | 手机校验 Desktop 证书指纹 | ✅ Phase 2（Android TrustManager 校验 DER SHA-256，不匹配抛 SSLHandshakeException） |
| 6 | 不信任上传文件名（服务端生成文件名，防 `../`） | ✅ Phase 1 |
| 7 | MIME 白名单 + 文件头校验 | ✅ Phase 1 |
| 8 | 单文件 ≤ 25 MB | ✅ Phase 1 |
| 9 | SHA-256 完整性校验 | ✅ Phase 1 |
| 10 | 无公网监听 | ✅ Phase 1（仅绑定本机网卡，防火墙未自动开洞） |
| 11 | 不自动打开收到的可执行文件（MVP 仅图片） | ✅ Phase 1（仅接受 image/jpeg、png、webp） |
| 12 | API Key 系统安全存储 | Phase 5 |
| 13 | 网络输入长度/格式校验 | ✅ Phase 1（transferId/sha/文件名长度与格式校验、metadata ≤16KB）+ Phase 2（QR ≤2048、端口/指纹格式校验） |
| 14 | QR 解析失败不 crash | ✅ Phase 2（Android 捕获解析异常并提示，不崩溃） |
| 15 | mDNS TXT 不含敏感信息 | ✅ Phase 2（仅 version/deviceId/name，无 token/指纹） |

## 本地存储

```
%LOCALAPPDATA%\PhoneLink\
├─ data\
│  ├─ phonelink.db          元数据（无裸 token，token 仅存 SHA-256 哈希）
│  └─ dev-token.txt         Phase 1 开发期测试 Token（Phase 2 已弃用，仅兼容旧客户端）
├─ inbox\YYYY-MM-DD\        <transferId>.<ext>（文件名由服务端生成）
├─ thumbnails\
├─ logs\
└─ temp\                    上传中间文件，成功后原子移入 inbox，失败即清理
```

上传流程：temp 写入 → 大小/MIME 文件头/hash 校验 → 原子移动 → SQLite 记录。

## Android 端安全实现

- 配对 token（Device Token）加密存储：AES-256-GCM，密钥保存在 Android Keystore（不可导出），仅本应用可解密。
- TLS 信任锚为桌面证书指纹：OkHttp 自定义 `X509TrustManager` 握手时比对 DER SHA-256，不匹配抛 `SSLHandshakeException`，无任何放行路径。
- 证书指纹不匹配时 UI 明确提示"桌面证书与已配对指纹不一致"，引导重新扫码配对，不回退到明文/无验证连接。
- 单次配对二维码包含一次性 token，扫码后即失效（防重放）。

## Phase 1 遗留说明（已收口）

- `dev-token.txt`：Phase 1 开发期 Token 明文存放，Phase 2 起不生成、鉴权优先走 Device Token，仅保留旧客户端兼容读取。
- Desktop TLS 证书存放于当前用户证书库（Windows DPAPI 保护），Phase 2 已使用其 SHA-256 fingerprint 做客户端钉扎；证书更换后需重新配对（安全拒绝，无降级）。