# Phone-Link Project Status

## Current Phase
Phase 4B-D2 — Local User Correction Dataset

## Status
DONE (2026-08-16)

## Last Verified Commit
`feat(scanner): add Phase 4B-D2 local user correction dataset`

## Completed

### Phase 0 — Bootstrap
- [x] 仓库结构 / 解决方案 / 双端骨架 / 验证细节记录

### Phase 1 — Windows Local Receiver
- [x] 本机 DeviceIdentity（`desktop-<guid>`，SQLite settings 持久化）
- [x] SQLite 持久化（transfers + settings 表，Microsoft.Data.Sqlite）
- [x] GET /v1/health：无 token 最小响应 / 有效 token 完整身份
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

### Phase 3 — Camera → PC Flow
- [x] Android 首页改为 CameraX 实时预览相机页（快门拍照 + 相册 Photo Picker 入口）
- [x] 相机权限门控（CameraPermissionGate），拒绝后引导系统设置
- [x] 拍照方向：重力传感器驱动 ImageCapture TargetRotation（竖拿竖拍、横拿横拍），不依赖系统"自动旋转"设置；Activity 锁定竖屏
- [x] 图片预处理（ImagePreparer）：EXIF orientation 旋转为实际像素方向 → JPEG quality 95、最长边 ≤4096（可读性优先）→ 流式 SHA-256
- [x] 上传（TransferRepository）：multipart metadata 先于 file（协议约束）、64KB 分块、真实字节进度、钉扎 TLS + Device Token
- [x] 传输状态机（TransferViewModel）：Idle/Preparing/Preview/Uploading/Completed/Failed，2% 进度节流
- [x] 幂等重试：重试复用同一 TransferId；上传结果不确定时 GET /v1/transfers/{id} 确认，已存在则跳过，绝不重复落盘
- [x] 失败分类（TransferErrorClassifier）：SERVICE_PAUSED 503、DEVICE_REVOKED 403、AUTH_INVALID 401、指纹不匹配、网络超时等 → 中文用户文案 + 可重试标记
- [x] 发送页 UI（SendScreens）：预览（重拍/发送）、上传进度、成功自动返回（1.2s）、失败（重试/放弃）
- [x] 临时文件：拍照原始文件 cache/camera，规范化文件 cache/transfers；启动清理旧临时文件
- [x] Desktop 暂停接收：IReceiverHost.Pause()/Resume()，暂停时上传返回 503 SERVICE_PAUSED，UI 暂停/恢复按钮
- [x] Desktop Latest 操作栏：打开 / 复制图片（剪贴板）/ 打开所在文件夹
- [x] Desktop 系统托盘：打开主窗口 / 暂停接收（勾选）/ 配对新手机 / 打开收件文件夹 / 退出；关闭窗口隐藏到托盘
- [x] Desktop 测试：PauseTests（3）+ LatestActionsTests（7，新测试项目 PhoneLink.Desktop.Tests）
- [x] Android JVM 单测新增：TransferManifest 3 + TransferErrorClassifier 11 + SHA-256 流式 2

### Phase 3.1 — Camera & Desktop UX Polish
- [x] 拍照 → 预览（所见即所得，Camera Preview ≈ Captured Image，EXIF orientation 管线稳定）
- [x] 发送页交互完善：重拍 / 发送 / 上传进度 / 成功自动返回 / 失败重试或放弃
- [x] 双击 Desktop Latest 打开图片；状态栏接收状态文案与状态点颜色
- [x] Android 深色风格统一（相机 / 预览 / 上传 / 失败页）

### Phase 3.2 — Product Shell
- [x] Android Home Screen 三段式布局（相机入口 / 相册 / 设备状态）
- [x] Android 设备设置底部抽屉（ModalBottomSheet，深色，显示设备信息 / 断开 / 重新配对）
- [x] Android 导航状态机（Home / Camera 互切，Crossfade，BackHandler；发送成功 900ms 自动回 Camera）
- [x] Windows 主界面重构：1120×760（Min 900×620），Latest + Recent Filmstrip + 操作栏 + 设备区
- [x] App.xaml 全局样式体系（字体 / 标题 / 按钮 / Filmstrip tile / 列表）
- [x] 设备管理窗口 + 配对窗口统一新风格；恢复「＋ 配对新手机」入口
- [x] MainViewModel 状态：等待手机连接 / 接收中·设备名 / 已暂停接收，状态点颜色，4s 回落

### Phase 3.2.1 — Filmstrip Scrolling
- [x] Recent Filmstrip 横向滚轮滚动（悬停即可，无需 Shift）
- [x] 点击选中后自动 ScrollIntoView
- [x] Tile 几何统一（132 宽 / 116×80 缩略图 / 时间居中）；Android Home 蓝色圆形拍题入口

### Phase 3.2.2 — Recent Strip Scrolling Behavior
- [x] 根因修复：逻辑滚动（CanContentScroll=True）下 ScrollToHorizontalOffset 以 item 为单位的跳页问题 → 像素滚动
- [x] 旧 ScrollBar 模板内 PART_HorizontalScrollBar Thumb 灰块残留 → 结构移除（ScrollBar → Slider）
- [x] 底部位置控制条改为 `Slider`（Minimum=0 / Maximum=ScrollableWidth / Value=HorizontalOffset 直接 1:1）
- [x] 数值诊断验证：最左 Value=0→Offset=0；中间 Value=952→Offset=952（50% 精确）；最右 Value=1904→Offset=1904（误差 0）
- [x] 真实鼠标拖拽验收通过：Thumb 可完整拖到 Track 左右两端（不再卡 2/3）；滚轮联动；时间不裁切（viewport 128）；点击选中正常；无溢出时整条隐藏
- [x] Track #E6E8EC 4px 圆角 2 全宽；Thumb #A6ACB5 46×10 圆角 5 固定宽；FlowDirection 显式 LTR

### Phase 4B-D2 — Local User Correction Dataset (2026-08-16)
- [x] 采集纯 Kotlin 层（`scanner/feedback/`）：ScannerFeedbackConfig（阈值/采样率/队列上限集中）、ScannerFeedbackMetadata（Schema V1 JSON）、ScannerFeedbackMath（归一化 delta / 确定性 5% 采样）、ScannerFeedbackDecision（A/B/C/D/F 规则）、FeedbackQueuePolicy（淘汰优先级）、ScannerFeedbackCollector（session/confirm/pending/上传）
- [x] 生命周期：进入 AdjustingEdges 每次创建 Session（记录 Initial Prediction）；仅「下一步」确认才采样；返回/取消/使用整张图片不采集；开关默认 OFF（SecureStore 持久化）
- [x] 决策规则：NOT_FOUND→MODEL_NOT_FOUND（predictedQuad=null）；LOW_CONFIDENCE 总是采集；DETECTED+maxDelta≥0.003→USER_CORRECTED；DETECTED+无调整→hash(sampleId)%100<5 采样 CLEAN_SUCCESS
- [x] 上传：POST /api/v1/scanner-feedback multipart（metadata 先于 file），复用 EndpointResolver + TLS 指纹钉扎 + Device Token，best effort 不阻塞主发送，成功 ACK 后删本地包
- [x] Windows 落盘：`%LOCALAPPDATA%\PhoneLink\scanner-feedback\yyyy-MM\<sampleId>\{source.jpg, metadata.json}`，tmp 写入 → SHA-256/25MB/JPEG 头校验 → 原子 rename；sampleId 幂等（重复返回 already_stored 不重复落盘）
- [x] 队列上限 100 样本 / 500MB；淘汰优先级 CLEAN_SUCCESS < LOW_CONFIDENCE < USER_CORRECTED < MODEL_NOT_FOUND，同级删最旧
- [x] Android 设置 UI：Home 设备抽屉「保存扫描纠错样本」Switch（默认关闭，说明文案：仅保存在已配对电脑、不上传云端）
- [x] Desktop：ScannerFeedbackMetadata Parser（严格 Schema V1 校验）+ IScannerFeedbackService + ScannerFeedbackService + ErrorCodes（FEEDBACK_INVALID/TOO_LARGE/HASH_MISMATCH）+ DI 接线
- [x] 工具：tools/scanner-feedback/audit_feedback_dataset.py（stdlib 审计：总数/reason 分布/delta 统计/角调整次数/损坏/重复 SHA）+ generate_feedback_contact_sheet.py（Pillow 联系表：原图 + 预测红框 + 修正绿框）
- [x] 测试：Android JVM 新增 20（数学 6 + 决策 5 + collector 4 + 队列策略 2 + …，共 120）；Desktop 新增 19（parser 11 + 集成 8，共 133）
- [x] 隐私：样本 = detector 真实输入 prepared 原图（warp 之前）；prepared JPEG 无 EXIF（Bitmap.compress 重编码）；不存 GPS/序列号等；数据不上云、不第三方、不训练

## Verification
- Desktop build: ✅ 0 error
- Desktop tests: ✅ 133/133（Core 33、Transport 28、Integration 57、Desktop.Tests 15）
- Android unit tests: ✅ 120/120（原 100 + Phase 4B-D2 新增 20）
- Android build: ✅ assembleDebug 0 error
- 实机验收（MEIZU 21 + 真实 Wi-Fi，Phase 3）:
  - ✅ TEST A：拍照 → 预览 → 发送 → PC 自动显示（Latest 更新）
  - ✅ TEST B：方向正确——竖拍 3000×4000、横拍 4000×3000（EXIF=6/1 验证，系统自动旋转关闭状态）
  - ✅ TEST C：复制图片 → 剪贴板含 4000×3000 位图；打开 / 打开所在文件夹正常
  - ✅ TEST D：相册选图发送成功（长图 2292×1690、截图 2292×436）
  - ✅ TEST E：断网发送失败 → 恢复网络重试成功，PC 端仅一个文件（同 TransferId 幂等，无重复）
  - ✅ TEST F：桌面端重启后 Latest 恢复（磁盘历史）、手机无需重新配对
  - ✅ TEST G：撤销设备后发送被拒（"设备已被桌面端撤销"）→ 重新扫码配对恢复
  - ✅ TEST H：托盘暂停接收 → 发送失败（503 SERVICE_PAUSED 提示）→ 恢复后成功
- 性能实测（同 Wi-Fi）:
  - ✅ 2.2MB JPEG 上传 267ms（手机端 upload 计时）；434KB 上传 121ms
  - ✅ 服务端处理 434KB 请求 118ms / 上传事务 60ms
  - ✅ 目标 2MB < 2s 达成（实测约 0.27s，含上传）

## Known Issues
- Windows 防火墙：首次监听局域网端口可能弹出允许提示，需用户允许（文档已说明）
- Flyme/部分厂商 ROM 限制 adb install：实机安装需 push APK 后从文件管理器手动安装
- Flyme 系统日志优化：Log.d 不输出到 logcat（不影响功能，调试用文件日志替代）
- 手机端无日志文件（验收用临时钩子已移除，后续如需可加正式日志）
- Phase 4B-D2 实机验收待执行（手机端开启开关 → 扫描并手动调整边缘 → 确认 → PC 端 scanner-feedback 目录出现样本包；数据集审计脚本 tools/scanner-feedback/audit_feedback_dataset.py）

## Next Action
- Phase 4B-D2 实机验收（上述 Known Issues 条目）
- Phase 4B（自动文档检测后续）/ 多页批量扫描 / OCR 暂不开始