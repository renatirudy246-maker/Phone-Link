# Testing

## 命令

```powershell
# 全部 .NET 测试
.\tools\build-desktop.ps1          # build + test

# Android 构建验证
.\tools\build-android.ps1          # assembleDebug
```

## 分层

- **单元测试**（`PhoneLink.Core.Tests` / `PhoneLink.Transport.Tests`）：模型、错误码、纯逻辑。
- **集成测试**（`PhoneLink.IntegrationTests`）：跨项目行为（Phase 1 起：SQLite + 文件落盘 + 上传管道）。
- **协议冒烟**（`tools/protocol-smoke-test`，Phase 1）：真实 HTTP 请求打本机服务。
- **手工验证**（涉及真实手机/硬件，各 Phase 验收时执行）。

## 测试矩阵（按 Phase 落实）

| 类别 | 场景 | 对应 Phase |
| --- | --- | --- |
| Network | 同一 Wi-Fi / PC 换 IP / 手机换 IP / Wi-Fi 断连 / 上传中断 / 两端重启 | 1–3 |
| Image | 竖拍 / 横拍 / 大 JPG / PNG / 暗光 / EXIF 旋转 / 非法图片 / 伪造 MIME | 1–3 |
| Pairing | 有效 QR / 过期 QR / 复用 QR / 错误指纹 / 撤销设备 / 未知设备 / 畸形 QR | 2 |
| Storage | 磁盘不可用 / 只读失败 / temp 清理 / 重启持久化 / 重复 transferId | 1 |

## 当前状态

- Phase 0：Core 模型与错误码单元测试 24 项通过；Android debug 构建通过。
- 其余矩阵项随对应 Phase 补充。