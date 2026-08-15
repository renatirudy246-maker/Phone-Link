# Phone-Link Project Status

## Current Phase
Phase 2 — Secure QR Pairing + Discovery

## Status
COMPLETED (2026-08-15)

## Last Verified Commit
Phase 0: e1bb9bf（Phase 1 提交：`phase-1: add local image receiver`）

## Completed

### Phase 1 — Windows Local Receiver
- [x] 仓库结构 / 解决方案 / 双端骨架（Phase 0）
- [x] Desktop 启动本地 HTTPS Receiver（Kestrel，监听 0.0.0.0:8484，自签证书）
- [x] 本机 DeviceIdentity（`desktop-<guid>`，SQLite settings 持久化）
- [x] SQLite 持久化（transfers + settings 表，Microsoft.Data.Sqlite）
- [x] GET /v1/health：无 token 最小响应（仅协议版本）/ 有效 token 完整身份
- [x] POST /v1/transfers：multipart 上传（metadata + file，metadata 必须在 file 前）
- [x] GET /v1/transfers/{id}：状态确认（幂等/重试去重用）
- [x] Transfer 管线：temp 写入 → 25MB 上限 → MIME 白名单+文件头校验 → SHA-256 → 原子移动 → SQLite → 事件 → UI
- [x] 服务端生成文件名 `<transferId>.<ext>`，不使用客户端路径/文件名（防路径穿越）
- [x] 异常路径：伪造 MIME、超 25MB、hash mismatch、路径穿越、重复 transferId（幂等）、写盘失败、坏 JSON、缺 part
- [x] 事件总线（TransferEventBus）：文件接收/哈希在后台线程，UI 通过 Dispatcher 更新，不阻塞 UI
- [x] Desktop UI：Latest 图片 + Recent 列表，全部来自真实 SQLite 数据；重启后历史恢复
- [x] tools/protocol-smoke-test：不依赖手机的端到端冒烟（14 场景全部 PASS，含重启持久化）
- [x] 日志：Serilog 文件日志（%LOCALAPPDATA%\PhoneLink\logs\），短 ID，无 token/Authorization 泄露

### Phase 2 — Secure QR Pairing + Discovery
- [x] TLS 身份持久化：首次运行生成自签 RSA-2048 证书，存入当前用户证书库（DPAPI），重启复用同一证书
- [x] PairingSession：3 分钟 TTL、一次性 token（256-bit）、过期/复用即作废，SQLite 持久化
- [x] POST /v1/pair：一次性 token 换每设备长期 Device Token（token 仅存 SHA-256 哈希）
- [x] 每设备 token 鉴权：/v1/health、/v1/transfers 均要求 Bearer Device Token；未知/被撤销设备拒绝
- [x] 设备撤销：Windows 端按设备 ID 撤销，撤销后手机请求立即 401/403（DEVICE_REVOKED）
- [x] 安全收口：Dev Token 弃用（不再生成，鉴权优先 Device Token）、health 区分"无 token 预配对"与"无效 token 拒绝"
- [x] mDNS 广告：Windows 内建 DNS-SD（dnsapi.dll）发布 `_phonelink._tcp.local`，TXT 仅 version/deviceId/name
- [x] Android 扫码配对：CameraX 预览 + ZXing 解码（旋转/分辨率处理）→ QR 解析 → TLS 握手
- [x] Android 指纹钉扎：自定义 X509TrustManager 校验 DER SHA-256，不匹配抛 SSLHandshakeException，绝不放行
- [x] Android 安全存储：Device Token AES-256-GCM 加密存 SharedPreferences，密钥在 Android Keystore
- [x] Android 自动重连：NSD 发现桌面端 → health 验证 → 已连接状态；发现超时回退已保存 host/port
- [x] Android 状态机：未配对 / 扫码中 / 连接中 / 已连接 / 离线 / 已撤销 / 指纹不一致，错误分类明确文案
- [x] Android 单测（JVM）：QR 编解码 6 + 指纹规范化 4，全部通过
- [x] 工具：tools/qr-show（show/verify/devices/revoke/restore/reset-cert）、tools/qr-fullscreen（全屏 QR）
- [x] 文档：PROTOCOL.md / SECURITY.md / TESTING.md / README.md 已按 Phase 2 更新

## Verification
- Desktop build: ✅ 0 warning / 0 error
- Desktop tests: ✅ 93/93（Core 22、Transport 28、Integration 43）
- Android unit tests: ✅ 10/10（QrPayloadCodec 6、Fingerprints 4）
- Smoke test: ✅ 14/14（真实 Desktop 应用，含重启持久化）
- mDNS: ✅ 日志确认 `mDNS advertisement registered: DESKTOP-I3G6SEO._phonelink._tcp.local (port 8484)`
- 实机验收（MEIZU 21 + 真实 Wi-Fi）:
  - ✅ 扫码配对成功（QR → 指纹钉扎 → /v1/pair → Device Token 持久化）
  - ✅ 重启 app 自动重连（NSD 发现 → health → "已连接"）
  - ✅ 撤销设备：手机 health 返回 DEVICE_REVOKED，UI 提示"已被撤销，请重新扫码配对"
  - ✅ 错误指纹拒绝：更换桌面证书后重连，`SSLHandshakeException: Certificate fingerprint mismatch`，UI 明确提示"指纹不一致"

## Known Issues
- 无托盘（规范 §11.2 属于 UI 要求，未列入 Phase 1/2 验收，建议 Phase 3 前补）
- `dev-token.txt` 遗留文件（Phase 2 起不生成、不鉴权，仅兼容旧客户端读取，可手动删除）
- Windows 防火墙：首次监听局域网端口可能弹出允许提示，需用户允许（文档已说明）
- Flyme/部分厂商 ROM 限制 adb install：实机安装需 push APK 后从文件管理器手动安装
- 上传 metadata 要求先于 file part（协议文档已明确，Phase 3 实现客户端时遵守）
- Android 上传/图片发送功能尚未实现（属 Phase 3 范围）

## Next Action
- 提交 Phase 2（建议 commit: `phase-2: add secure QR pairing and discovery`）
- 用户确认后进入 Phase 3：Android 拍照/相册发送 + 上传管线（multipart 遵守 metadata 先于 file 的协议约束）