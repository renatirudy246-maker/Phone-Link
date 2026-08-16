package com.phonelink.app.transfer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonelink.app.crop.CropMath
import com.phonelink.app.crop.CropRectF
import com.phonelink.app.network.PinnedClientFactory
import com.phonelink.app.pairing.SecureStore
import com.phonelink.app.scanner.DocumentDetector
import com.phonelink.app.scanner.DocumentDetectionResult
import com.phonelink.app.scanner.DocumentEnhancer
import com.phonelink.app.scanner.EnhanceMode
import com.phonelink.app.scanner.PerspectiveTransformer
import com.phonelink.app.scanner.Quadrilateral
import com.phonelink.app.scanner.QuadrilateralMath
import com.phonelink.app.scanner.DetectionStatus
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 传输状态机：Idle → Preparing → Detecting → AdjustingEdges → ScanPreview → Uploading → Completed / Failed。
 * 文档扫描：Original（方向归一化原图）→ 四边形 → 透视校正 base → 增强 → 裁切（可选）→ 发送目标。
 * 幂等：一次拍摄生成一个 transferId；不确定结果先 GET 状态再决定重试（复用同一 ID）。
 */
sealed interface SendUiState {
    data object Idle : SendUiState
    data class Preparing(val message: String) : SendUiState

    /** 拍照后高质量文档检测中。 */
    data class Detecting(val sourceFile: File) : SendUiState

    /** 调整边缘：四角可拖。status 区分检测结果提示。 */
    data class AdjustingEdges(
        val sourceFile: File,
        val quad: Quadrilateral,
        val status: DetectionStatus,
    ) : SendUiState

    /** 扫描结果预览（发送目标 working）。 */
    data class ScanPreview(val previewFile: File) : SendUiState
    data class Cropping(val sourceFile: File) : SendUiState
    data class Uploading(val percent: Int) : SendUiState
    data class Completed(val desktopName: String) : SendUiState
    data class Failed(val failure: TransferFailure, val transferId: String?) : SendUiState
}

class TransferViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val tempDir = File(app.cacheDir, "transfers")

    /** 原始图片（方向归一化后），整个编辑流程保留，供重新扫描/调整/恢复。 */
    private var prepared: ImagePreparer.PreparedImage? = null

    /** 当前四边形（null = 未扫描/使用整张图片）。 */
    private var scanQuad: Quadrilateral? = null

    /** 透视校正后的 base 文件（增强/裁切的数据源，不再二次 warp）。 */
    private var scanBase: ImagePreparer.PreparedImage? = null

    private var enhanceMode: EnhanceMode = EnhanceMode.ORIGINAL

    /** 当前工作图片（发送目标）。 */
    private var working: ImagePreparer.PreparedImage? = null
    private var manifest: TransferManifest? = null
    private var transferId: String? = null
    private var sending = false

    var sendState by mutableStateOf<SendUiState>(SendUiState.Idle)
        private set

    /** 是否已矩形裁切（ScanPreview 显示"已裁切"，禁用增强切换）。 */
    var cropped by mutableStateOf(false)
        private set

    /** 当前增强模式（ScanPreview UI 展示）。 */
    var currentEnhanceMode by mutableStateOf(EnhanceMode.ORIGINAL)
        private set

    /** 最近一次 Preview 的来源：true=相机拍摄（重拍/成功后回 Camera），false=相册（回 Home）。 */
    var previewFromCamera by mutableStateOf(true)
        private set

    init {
        cleanupStaleTempFiles()
    }

    val desktopName: String
        get() = store.readDesktopName() ?: "Desktop"

    /** 拍照完成回调（CameraX 已写入 cache 原图）→ 后台规范化 → 高质量检测。 */
    fun onCaptured(photoFile: File, capturedAt: OffsetDateTime) {
        if (sending) return
        previewFromCamera = true
        sendState = SendUiState.Preparing("正在处理图片…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    ImagePreparer.prepare(
                        context = getApplication(),
                        source = null,
                        sourceFile = photoFile,
                        outFile = nextTempFile("capture.jpg"),
                        sourceName = "capture.jpg",
                    )
                } catch (e: Exception) {
                    e
                }
            }
            photoFile.delete()
            when (result) {
                is ImagePreparer.PreparedImage -> {
                    enterPreview(result, capturedAt)
                    startDetection()
                }
                is Exception -> sendState = SendUiState.Failed(
                    TransferFailure(TransferFailureKind.PREPARE_FAILED, "图片处理失败：${result.message}"),
                    null,
                )
            }
        }
    }

    /** Gallery 选择（Content URI）→ 后台规范化 → 高质量检测。 */
    fun onGalleryPicked(uri: Uri) {
        if (sending) return
        previewFromCamera = false
        sendState = SendUiState.Preparing("正在处理图片…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    ImagePreparer.prepare(
                        context = getApplication(),
                        source = uri,
                        sourceFile = null,
                        outFile = nextTempFile("gallery.jpg"),
                        sourceName = "gallery.jpg",
                    )
                } catch (e: Exception) {
                    e
                }
            }
            when (result) {
                is ImagePreparer.PreparedImage -> {
                    enterPreview(result, OffsetDateTime.now())
                    startDetection()
                }
                is Exception -> sendState = SendUiState.Failed(
                    TransferFailure(TransferFailureKind.PREPARE_FAILED, "图片处理失败：${result.message}"),
                    null,
                )
            }
        }
    }

    private fun enterPreview(image: ImagePreparer.PreparedImage, capturedAt: OffsetDateTime) {
        prepared = image
        working = image
        scanQuad = null
        scanBase = null
        enhanceMode = EnhanceMode.ORIGINAL
        currentEnhanceMode = EnhanceMode.ORIGINAL
        cropped = false
        val id = "t-" + java.util.UUID.randomUUID().toString().replace("-", "")
        transferId = id
        rebuildManifest(image, id, capturedAt)
    }

    /** 高质量文档检测（在 Detecting 状态执行，完成后进入 AdjustingEdges）。 */
    private fun startDetection() {
        val original = prepared ?: return
        sendState = SendUiState.Detecting(original.file)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(original.file.absolutePath)
                        ?: return@withContext DocumentDetectionResult.NotFound(reason = "无法解码原图")
                    val r = DocumentDetector.detectHighQuality(getApplication(), bitmap)
                    bitmap.recycle()
                    r
                } catch (t: Throwable) {
                    Log.w(TAG, "document detection failed", t)
                    DocumentDetectionResult.NotFound(reason = "检测异常: ${t.message}")
                }
            }
            val status = when (result) {
                is DocumentDetectionResult.Detected -> DetectionStatus.DETECTED
                is DocumentDetectionResult.LowConfidence -> DetectionStatus.LOW_CONFIDENCE
                is DocumentDetectionResult.NotFound -> DetectionStatus.NOT_FOUND
            }
            val quad = when (result) {
                is DocumentDetectionResult.Detected -> result.quad
                is DocumentDetectionResult.LowConfidence -> result.quad
                is DocumentDetectionResult.NotFound -> result.defaultQuad
            }
            sendState = SendUiState.AdjustingEdges(original.file, quad, status)
        }
    }

    /** 确认四角：透视校正 → base → 进入扫描预览（一次 warp，基于原图）。 */
    fun confirmScan(quad: Quadrilateral) {
        val original = prepared ?: return
        val id = transferId ?: return
        if (sending) return
        val valid = QuadrilateralMath.isValid(quad)
        sendState = SendUiState.Preparing("正在扫描…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(original.file.absolutePath)
                        ?: throw ImagePreparer.PrepareException("无法解码图片")
                    val baseBitmap = if (valid) PerspectiveTransformer.warp(bitmap, quad) else bitmap
                    if (baseBitmap !== bitmap) bitmap.recycle()
                    ImagePreparer.crop(baseBitmap, com.phonelink.app.crop.CropRect(0, 0, baseBitmap.width, baseBitmap.height), nextTempFile("scanbase.jpg"))
                        .also { baseBitmap.recycle() }
                } catch (e: Exception) {
                    e
                }
            }
            when (result) {
                is ImagePreparer.PreparedImage -> {
                    scanBase?.file?.delete()
                    scanBase = result
                    scanQuad = quad
                    enhanceMode = EnhanceMode.ORIGINAL
                    currentEnhanceMode = EnhanceMode.ORIGINAL
                    cropped = false
                    working = result
                    rebuildManifest(result, id)
                    sendState = SendUiState.ScanPreview(result.file)
                }
                is Exception -> {
                    sendState = SendUiState.AdjustingEdges(original.file, quad, DetectionStatus.LOW_CONFIDENCE)
                }
            }
        }
    }

    /** 不扫描：使用整张图片进入扫描预览（可用增强，无透视）。 */
    fun useFullImage() {
        val original = prepared ?: return
        val id = transferId ?: return
        if (sending) return
        scanBase?.file?.delete()
        scanBase = null
        scanQuad = null
        enhanceMode = EnhanceMode.ORIGINAL
        currentEnhanceMode = EnhanceMode.ORIGINAL
        cropped = false
        working = original
        rebuildManifest(original, id)
        sendState = SendUiState.ScanPreview(original.file)
    }

    /** 从扫描预览回到调整边缘（基于原图 + 上次四边形，不二次 warp）。 */
    fun reAdjustEdges() {
        val original = prepared ?: return
        if (sending) return
        sendState = SendUiState.AdjustingEdges(original.file, scanQuad ?: Quadrilateral.DEFAULT, DetectionStatus.DETECTED)
    }

    /** 切换增强（非破坏）：始终从 perspective base（或原图）重新生成。 */
    fun setEnhanceMode(mode: EnhanceMode) {
        if (mode == enhanceMode || mode == EnhanceMode.ORIGINAL && enhanceMode == EnhanceMode.ORIGINAL) return
        val base = scanBase ?: prepared ?: return
        val id = transferId ?: return
        if (sending || cropped) return
        if (mode == EnhanceMode.ORIGINAL) {
            // 回原始 base（未增强）
            enhanceMode = EnhanceMode.ORIGINAL
            currentEnhanceMode = EnhanceMode.ORIGINAL
            working = base
            rebuildManifest(base, id)
            sendState = SendUiState.ScanPreview(base.file)
            return
        }
        sendState = SendUiState.Preparing("正在优化页面…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(base.file.absolutePath)
                        ?: throw ImagePreparer.PrepareException("无法解码图片")
                    val enhanced = DocumentEnhancer.enhance(bitmap, mode)
                    bitmap.recycle()
                    ImagePreparer.crop(enhanced, com.phonelink.app.crop.CropRect(0, 0, enhanced.width, enhanced.height), nextTempFile("enhanced.jpg"))
                        .also { enhanced.recycle() }
                } catch (e: Exception) {
                    e
                }
            }
            when (result) {
                is ImagePreparer.PreparedImage -> {
                    enhanceMode = mode
                    currentEnhanceMode = mode
                    working = result
                    rebuildManifest(result, id)
                    sendState = SendUiState.ScanPreview(result.file)
                }
                is Exception -> sendState = SendUiState.ScanPreview((working ?: base).file)
            }
        }
    }

    /** 恢复原图：清理扫描派生文件，发送目标回到 original。 */
    fun restoreOriginal() {
        val original = prepared ?: return
        val id = transferId ?: return
        if (sending) return
        scanBase?.file?.delete()
        working?.file?.takeIf { it != original.file && it != scanBase?.file }?.delete()
        scanBase = null
        scanQuad = null
        enhanceMode = EnhanceMode.ORIGINAL
        currentEnhanceMode = EnhanceMode.ORIGINAL
        cropped = false
        working = original
        rebuildManifest(original, id)
        sendState = SendUiState.ScanPreview(original.file)
    }

    /** 进入矩形裁切（基于当前 working，4A 能力复用）。 */
    fun enterCrop() {
        if (sending) return
        val image = working ?: return
        sendState = SendUiState.Cropping(image.file)
    }

    /** 取消裁切：不影响 working，回到扫描预览。 */
    fun cancelCrop() {
        if (sending) return
        val image = working ?: return
        sendState = SendUiState.ScanPreview(image.file)
    }

    /** 确认矩形裁切：基于当前 working 文件全分辨率一次 JPEG 编码，Manifest 重算。 */
    fun confirmCrop(rect: CropRectF) {
        val image = working ?: return
        val id = transferId ?: return
        if (sending) return
        sendState = SendUiState.Preparing("正在处理图片…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val pixels = CropMath.normalizedToPixels(rect, image.width, image.height)
                    ImagePreparer.crop(image.file, pixels, nextTempFile("cropped.jpg"))
                } catch (e: Exception) {
                    e
                }
            }
            when (result) {
                is ImagePreparer.PreparedImage -> {
                    working?.file?.takeIf { it != prepared?.file }?.delete()
                    working = result
                    cropped = true
                    rebuildManifest(result, id)
                    sendState = SendUiState.ScanPreview(result.file)
                }
                is Exception -> sendState = SendUiState.ScanPreview(image.file)
            }
        }
    }

    /** 依据当前发送目标重建 Manifest（宽高/SHA/大小必须与最终发送文件一致）。 */
    private fun rebuildManifest(
        image: ImagePreparer.PreparedImage,
        id: String,
        capturedAt: OffsetDateTime = manifest?.let { parseTimestamp(it.capturedAt) } ?: OffsetDateTime.now(),
    ) {
        manifest = TransferManifest(
            transferId = id,
            senderDeviceId = manifest?.senderDeviceId ?: TransferIds.newSenderDeviceId(),
            originalFileName = "$id.jpg",
            mimeType = "image/jpeg",
            fileSize = image.fileSize,
            width = image.width,
            height = image.height,
            sha256 = image.sha256,
            capturedAt = formatTimestamp(capturedAt),
            sentAt = formatTimestamp(OffsetDateTime.now()),
            purpose = "Question",
        )
    }

    private fun parseTimestamp(value: String): OffsetDateTime = try {
        OffsetDateTime.parse(value)
    } catch (_: Exception) {
        OffsetDateTime.now()
    }

    fun retake() {
        clearPrepared()
        sendState = SendUiState.Idle
    }

    private val endpointResolver = com.phonelink.app.discovery.EndpointResolver(app, store)

    /** 发送：自动解析/自愈端点；失败不确定时 GET 状态；Completed → 本地标成功，绝不重复上传。 */
    fun send() {
        val image = working ?: prepared ?: return
        val currentManifest = manifest ?: return
        if (sending) return
        sending = true

        sendState = SendUiState.Uploading(0)
        viewModelScope.launch {
            val paired = store.readPairedDesktop() ?: run {
                finishFailed(TransferFailure(TransferFailureKind.AUTH_INVALID, "配对信息缺失，请重新配对。"), currentManifest.transferId)
                return@launch
            }

            var endpoint = withContext(Dispatchers.IO) {
                endpointResolver.resolve(paired, store.readEndpointCache())
            }

            if (endpoint == null) {
                finishFailed(
                    TransferFailure(TransferFailureKind.DESKTOP_OFFLINE, "未找到已配对电脑，请确认手机与电脑在同一局域网或热点。", canRetry = true),
                    currentManifest.transferId,
                )
                return@launch
            }

            var repo = buildRepository(endpoint.host, endpoint.port, paired)
            var uploadResult = withContext(Dispatchers.IO) {
                repo.upload(currentManifest, image.file) { written, total ->
                    val percent = if (total <= 0) 0 else ((written * 100) / total).toInt()
                    updateProgressThrottled(percent)
                }
            }

            // 若发生网络 IO 异常（IP 切换/漫游），自动触发一次端点发现自愈，并复用同一 TransferId 幂等重传
            if (uploadResult is TransferRepository.UploadResult.IoError) {
                Log.w(TAG, "Upload failed on ${endpoint.host}:${endpoint.port}, attempting endpoint recovery...")
                val recoveredEndpoint = withContext(Dispatchers.IO) {
                    endpointResolver.recover(paired)
                }
                if (recoveredEndpoint != null && (recoveredEndpoint.host != endpoint.host || recoveredEndpoint.port != endpoint.port)) {
                    Log.i(TAG, "Recovered new endpoint ${recoveredEndpoint.host}:${recoveredEndpoint.port}, retrying upload once...")
                    endpoint = recoveredEndpoint
                    repo = buildRepository(endpoint.host, endpoint.port, paired)
                    uploadResult = withContext(Dispatchers.IO) {
                        repo.upload(currentManifest, image.file) { written, total ->
                            val percent = if (total <= 0) 0 else ((written * 100) / total).toInt()
                            updateProgressThrottled(percent)
                        }
                    }
                }
            }

            when (uploadResult) {
                is TransferRepository.UploadResult.Success -> {
                    onCompleted()
                }
                is TransferRepository.UploadResult.HttpError -> {
                    val failure = TransferErrorClassifier.fromHttp(uploadResult.statusCode, uploadResult.code)
                    if (failure.kind == TransferFailureKind.DEVICE_REVOKED ||
                        failure.kind == TransferFailureKind.AUTH_INVALID
                    ) {
                        store.clear()
                    }
                    finishFailed(failure, currentManifest.transferId)
                }
                is TransferRepository.UploadResult.IoError -> {
                    // 结果不确定 → 查询状态（幂等核心）
                    val status = withContext(Dispatchers.IO) { repo.getStatus(currentManifest.transferId) }
                    when (status) {
                        is TransferRepository.StatusResult.Completed -> onCompleted()
                        is TransferRepository.StatusResult.NotFound,
                        is TransferRepository.StatusResult.Failed,
                        is TransferRepository.StatusResult.Error,
                        -> finishFailed(
                            TransferErrorClassifier.fromIo(uploadResult.exception),
                            currentManifest.transferId,
                        )
                    }
                }
            }
        }
    }

    /** 失败后重试：复用同一 TransferId 与已准备文件（避免 PC 收到重复题目）。 */
    fun retry() {
        send()
    }

    private fun buildRepository(host: String, port: Int, paired: com.phonelink.app.discovery.PairedDesktop): TransferRepository {
        return TransferRepository(
            clientFactory = { fp -> PinnedClientFactory.create(fp) },
            host = host,
            port = port,
            fingerprint = paired.certificateFingerprint,
            token = paired.deviceToken,
        )
    }

    private fun onCompleted() {
        sending = false
        clearPrepared()
        sendState = SendUiState.Completed(desktopName)
    }

    private fun finishFailed(failure: TransferFailure, id: String) {
        sending = false
        sendState = SendUiState.Failed(failure, id)
    }

    private var lastReportedPercent = -1

    private fun updateProgressThrottled(percent: Int) {
        if (percent != lastReportedPercent && percent % 2 == 0 || percent >= 100) {
            lastReportedPercent = percent
            sendState = SendUiState.Uploading(percent)
        }
    }

    /** Completed 显示后返回相机（用户点击"返回相机"或自动）。 */
    fun done() {
        clearPrepared()
        sendState = SendUiState.Idle
    }

    private fun clearPrepared() {
        prepared?.file?.delete()
        scanBase?.file?.delete()
        working?.file?.takeIf { it != prepared?.file && it != scanBase?.file }?.delete()
        prepared = null
        scanBase = null
        working = null
        scanQuad = null
        enhanceMode = EnhanceMode.ORIGINAL
        currentEnhanceMode = EnhanceMode.ORIGINAL
        cropped = false
        manifest = null
        transferId = null
        lastReportedPercent = -1
    }

    private fun nextTempFile(prefix: String): File {
        tempDir.mkdirs()
        return File(tempDir, "$prefix-${System.currentTimeMillis()}.jpg")
    }

    /** 启动清理：删除临时目录陈旧文件（不在本次会话内存跟踪中的文件）。 */
    private fun cleanupStaleTempFiles() {
        try {
            tempDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun formatTimestamp(value: OffsetDateTime): String =
        value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        private const val TAG = "PhoneLinkTransfer"
    }
}