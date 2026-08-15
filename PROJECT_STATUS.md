# Phone-Link Project Status

## Current Phase
Phase 1 — Windows Local Receiver

## Status
COMPLETED (2026-08-15)

## Last Verified Commit
Phase 0: e1bb9bf（含状态补充提交）

## Completed
- [x] 仓库结构 / 解决方案 / 双端骨架（Phase 0）
- [x] Desktop 启动本地 HTTPS Receiver（Kestrel，监听 0.0.0.0:8484，自签证书）
- [x] 本机 DeviceIdentity（`desktop-<guid>`，SQLite settings 持久化）
- [x] SQLite 持久化（transfers + settings 表，Microsoft.Data.Sqlite）
- [x] GET /v1/health：无 token 最小响应（仅协议版本）/ 有效 token 完整身份
- [x] POST /v1/transfers：multipart 上传（metadata + file，metadata 必须在 file 前）
- [x] GET /v1/transfers/{id}：状态确认（幂等/重试去重用）
- [x] Transfer 管线：temp 写入 → 25MB 上限 → MIME 白名单+文件头校验 → SHA-256 → 原子移动 → SQLite → 事件 → UI
- [x] 服务端生成文件名 `<transferId>.<ext>`，不使用客户端路径/文件名（防路径穿越）
- [x] 开发期 Token（Dev Token，256-bit 随机，与未来 Device Token 机制通过 ITokenValidator 解耦）
- [x] 异常路径：伪造 MIME、超 25MB、hash mismatch、路径穿越、重复 transferId（幂等）、写盘失败、坏 JSON、缺 part
- [x] 事件总线（TransferEventBus）：文件接收/哈希在后台线程，UI 通过 Dispatcher 更新，不阻塞 UI
- [x] Desktop UI：Latest 图片 + Recent 列表，全部来自真实 SQLite 数据；重启后历史恢复
- [x] tools/protocol-smoke-test：不依赖手机的端到端冒烟（13 场景全部 PASS，含重启持久化）
- [x] 日志：Serilog 文件日志（%LOCALAPPDATA%\PhoneLink\logs\），短 ID，无 token/Authorization 泄露
- [x] 文档：docs/PROTOCOL.md、docs/SECURITY.md、docs/TESTING.md 已按 Phase 1 更新

## Verification
- Desktop build: ✅ 0 warning / 0 error（含 tools/protocol-smoke-test）
- Desktop tests: ✅ 71/71（Core 22、Transport 28、Integration 21）
- Smoke test: ✅ 12/12 首轮 + 重启后 13/13（真实 Desktop 应用）
- 文件落盘: ✅ `%LOCALAPPDATA%\PhoneLink\inbox\2026-08-15\<transferId>.jpg/png`，SHA-256 与上传一致
- UI: ✅ 应用带真实历史启动正常（Latest/Recent 绑定 SQLite）
- 重启持久化: ✅ 重启后 GET /v1/transfers/{id} 返回 completed，DB 记录与文件保留
- 非法 MIME / >25MB / 路径穿越: ✅ 均被拒绝（415/413/安全命名）

## Known Issues
- 无托盘（规范 §11.2 属于 UI 要求，未列入 Phase 1 验收，建议 Phase 3 前补）
- Dev Token 明文存放 data/dev-token.txt（Phase 1 临时方案，Phase 2 移除并换每设备 Token）
- Windows 防火墙：首次监听局域网端口可能弹出允许提示，需用户允许（文档已说明）
- 上传 metadata 要求先于 file part（协议文档已明确，客户端实现时遵守）
- Android 端尚未实现网络功能（属 Phase 2/3 范围）

## Next Action
- 提交 Phase 1（建议 commit: `phase-1: add local image receiver`）
- 用户确认后进入 Phase 2：Secure QR Pairing + Discovery（TLS 身份持久化、PairingSession、/v1/pair、每设备 token、撤销、mDNS 发布、Android 扫码/指纹钉扎/NSD）