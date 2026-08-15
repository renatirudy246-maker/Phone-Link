package com.phonelink.app.transfer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonelink.app.network.PinnedClientFactory
import com.phonelink.app.pairing.SecureStore
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 传输状态机：Idle → Preparing → Preview → Uploading → Completed / Failed。
 * 幂等：一次拍摄生成一个 transferId；不确定结果先 GET 状态再决定重试（复用同一 ID）。
 */
sealed interface SendUiState {
    data object Idle : SendUiState
    data object Preparing : SendUiState
    data class Preview(val previewFile: File) : SendUiState
    data class Uploading(val percent: Int) : SendUiState
    data class Completed(val desktopName: String) : SendUiState
    data class Failed(val failure: TransferFailure, val transferId: String?) : SendUiState
}

class TransferViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val tempDir = File(app.cacheDir, "transfers")

    private var prepared: ImagePreparer.PreparedImage? = null
    private var manifest: TransferManifest? = null
    private var transferId: String? = null
    private var sending = false

    var sendState by mutableStateOf<SendUiState>(SendUiState.Idle)
        private set

    /** 最近一次 Preview 的来源：true=相机拍摄（重拍/成功后回 Camera），false=相册（回 Home）。 */
    var previewFromCamera by mutableStateOf(true)
        private set

    init {
        cleanupStaleTempFiles()
    }

    val desktopName: String
        get() = store.readDesktopName() ?: "Desktop"

    /** 拍照完成回调（CameraX 已写入 cache 原图）→ 后台规范化 → Preview。 */
    fun onCaptured(photoFile: File, capturedAt: OffsetDateTime) {
        if (sending) return
        previewFromCamera = true
        sendState = SendUiState.Preparing
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
                is ImagePreparer.PreparedImage -> enterPreview(result, capturedAt)
                is Exception -> sendState = SendUiState.Failed(
                    TransferFailure(TransferFailureKind.PREPARE_FAILED, "图片处理失败：${result.message}"),
                    null,
                )
            }
        }
    }

    /** Gallery 选择（Content URI）→ 后台规范化 → Preview。 */
    fun onGalleryPicked(uri: Uri) {
        if (sending) return
        previewFromCamera = false
        sendState = SendUiState.Preparing
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
                is ImagePreparer.PreparedImage -> enterPreview(result, OffsetDateTime.now())
                is Exception -> sendState = SendUiState.Failed(
                    TransferFailure(TransferFailureKind.PREPARE_FAILED, "图片处理失败：${result.message}"),
                    null,
                )
            }
        }
    }

    private fun enterPreview(image: ImagePreparer.PreparedImage, capturedAt: OffsetDateTime) {
        prepared = image
        val id = "t-" + java.util.UUID.randomUUID().toString().replace("-", "")
        transferId = id
        manifest = TransferManifest(
            transferId = id,
            senderDeviceId = TransferIds.newSenderDeviceId(),
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
        sendState = SendUiState.Preview(image.file)
    }

    fun retake() {
        clearPrepared()
        sendState = SendUiState.Idle
    }

    /** 发送：失败不确定时 GET 状态；Completed → 本地标成功，绝不重复上传。 */
    fun send() {
        val image = prepared ?: return
        val currentManifest = manifest ?: return
        if (sending) return
        sending = true

        sendState = SendUiState.Uploading(0)
        viewModelScope.launch {
            val repo = withContext(Dispatchers.IO) {
                buildRepository()
            } ?: run {
                sending = false
                sendState = SendUiState.Failed(
                    TransferFailure(TransferFailureKind.AUTH_INVALID, "配对信息缺失，请重新配对。"),
                    currentManifest.transferId,
                )
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                repo.upload(currentManifest, image.file) { written, total ->
                    val percent = if (total <= 0) 0 else ((written * 100) / total).toInt()
                    updateProgressThrottled(percent)
                }
            }

            when (result) {
                is TransferRepository.UploadResult.Success -> {
                    onCompleted()
                }
                is TransferRepository.UploadResult.HttpError -> {
                    val failure = TransferErrorClassifier.fromHttp(result.statusCode, result.code)
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
                        -> finishFailed(
                            TransferErrorClassifier.fromIo(result.exception),
                            currentManifest.transferId,
                        )
                        is TransferRepository.StatusResult.Error -> finishFailed(
                            TransferErrorClassifier.fromIo(result.exception),
                            currentManifest.transferId,
                        )
                    }
                }
            }
        }
    }

    /** 失败后重试：复用同一 TransferId 与已准备文件（避免 PC 收到重复题目）。 */
    fun retry() {
        val image = prepared ?: return
        val currentManifest = manifest ?: return
        if (sending) return
        sending = true

        sendState = SendUiState.Uploading(0)
        viewModelScope.launch {
            val repo = withContext(Dispatchers.IO) {
                buildRepository()
            } ?: run {
                sending = false
                sendState = SendUiState.Failed(
                    TransferFailure(TransferFailureKind.AUTH_INVALID, "配对信息缺失，请重新配对。"),
                    currentManifest.transferId,
                )
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                repo.upload(currentManifest, image.file) { written, total ->
                    val percent = if (total <= 0) 0 else ((written * 100) / total).toInt()
                    updateProgressThrottled(percent)
                }
            }

            when (result) {
                is TransferRepository.UploadResult.Success -> onCompleted()
                is TransferRepository.UploadResult.HttpError -> {
                    val failure = TransferErrorClassifier.fromHttp(result.statusCode, result.code)
                    if (failure.kind == TransferFailureKind.DEVICE_REVOKED ||
                        failure.kind == TransferFailureKind.AUTH_INVALID
                    ) {
                        store.clear()
                    }
                    finishFailed(failure, currentManifest.transferId)
                }
                is TransferRepository.UploadResult.IoError -> {
                    val status = withContext(Dispatchers.IO) { repo.getStatus(currentManifest.transferId) }
                    when (status) {
                        is TransferRepository.StatusResult.Completed -> onCompleted()
                        is TransferRepository.StatusResult.NotFound,
                        is TransferRepository.StatusResult.Failed,
                        is TransferRepository.StatusResult.Error,
                        -> finishFailed(
                            TransferErrorClassifier.fromIo(result.exception),
                            currentManifest.transferId,
                        )
                    }
                }
            }
        }
    }

    private fun buildRepository(): TransferRepository? {
        val token = store.readToken() ?: return null
        val fingerprint = store.readFingerprint() ?: return null
        val endpoint = store.readEndpoint() ?: return null
        return TransferRepository(
            clientFactory = { fp -> PinnedClientFactory.create(fp) },
            host = endpoint.first,
            port = endpoint.second,
            fingerprint = fingerprint,
            token = token,
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
        prepared = null
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