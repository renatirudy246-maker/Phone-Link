# Testing

## 命令

```powershell
# Desktop build + 全部 .NET 测试
.\tools\build-desktop.ps1

# Android debug 构建
.\tools\build-android.ps1

# 端到端冒烟测试（启动真实 Desktop 应用 → 本机上传 → 重启验证持久化）
.\tools\run-smoke-test.ps1
```

## 分层

- **单元测试**（`PhoneLink.Core.Tests` / `PhoneLink.Transport.Tests`）：模型、错误码、Bearer 解析、metadata 解析、错误映射、证书指纹。
- **集成测试**（`PhoneLink.IntegrationTests`）：进程内真实 Kestrel HTTPS + 真实 SQLite + 真实文件管道，覆盖全部上传管线与异常路径。
- **协议冒烟**（`tools/protocol-smoke-test`）：对运行中的真实 Desktop 应用做端到端验证。
- **手工验证**（涉及真实手机/硬件）：Phase 2–3 执行。

## 当前覆盖（Phase 1，71 项自动化测试 + 13 项冒烟场景）

### HTTP / 上传（IntegrationTests）
- health：无 token 最小响应 / 有效 token 完整信息 / 错误 token 最小响应
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

### 解析/映射（Transport.Tests）
- Bearer token 提取（大小写、空值、其他 scheme）
- metadata 校验：缺字段、非法 id 字符、坏 sha、非法 purpose、负 width、路径穿越/Windows 路径文件名剥离、时间戳缺省、坏 JSON
- 错误码 → HTTP 状态码映射

### 冒烟（真实应用）
- health × 2、JPEG/PNG 上传 + 落盘 + SHA-256、状态查询、404、伪造 MIME、>25MB、hash mismatch、路径穿越、幂等、401、重启后历史保留

## 测试矩阵

| 类别 | 场景 | 状态 |
| --- | --- | --- |
| Network | 同一 Wi-Fi / PC 换 IP / 手机换 IP / Wi-Fi 断连 / 上传中断 / 两端重启 | 重启已自动化；手机侧场景随 Phase 2–3 |
| Image | 竖拍 / 横拍 / 大 JPG / PNG / 暗光 / EXIF 旋转 / 非法图片 / 伪造 MIME | 大 JPG/PNG/伪造 MIME 已覆盖；EXIF 等随 Phase 3 |
| Pairing | 有效 QR / 过期 QR / 复用 QR / 错误指纹 / 撤销设备 / 未知设备 / 畸形 QR | Phase 2 |
| Storage | 磁盘不可用 / 只读失败 / temp 清理 / 重启持久化 / 重复 transferId | 已覆盖（temp 清理、重启持久化、重复 id、写盘失败） |