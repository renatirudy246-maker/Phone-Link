package com.phonelink.app.scanner.feedback

import com.phonelink.app.network.PinnedClientFactory
import com.phonelink.app.scanner.DetectionStatus
import com.phonelink.app.scanner.Quadrilateral
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject

/**
 * 本地扫描反馈采集器（LOCAL SCANNER FEEDBACK DATASET）。
 *
 * 生命周期：
 *   beginSession()        —— 进入"调整边缘"页时创建 Session（记录 Initial Prediction）
 *   confirmCorrection()   —— 用户点击"下一步"确认四边形后决策并打包到 pending 队列
 *   cancelSession()       —— 返回/放弃/使用整张图片时取消（不产生 GT）
 *   uploadPending()       —— 主发送成功后 best effort 补传（复用 EndpointResolver 解析的
 *                           endpoint + TLS fingerprint pinning + Device Token）
 *
 * 设计约束：
 * - 不阻塞主流程；上传失败保留 pending，等待下次连接重试；
 * - 队列上限：MAX_PENDING_SAMPLES / MAX_PENDING_BYTES，优先淘汰低价值样本；
 * - 纯 JVM 可测：目录与开关注入，无 Context 依赖（上传需要时传参）。
 */
class ScannerFeedbackCollector(
    val pendingDir: File,
    private val enabled: () -> Boolean,
) {

    data class SessionInfo(
        val sampleId: String,
        val createdAtUtc: String,
        val sourceFile: File,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sourceSha256: String,
        val status: FeedbackDetectionStatus,
        val confidence: Float?,
        val qualityReason: String?,
        val maskAreaRatio: Float?,
        val heatmap: FeedbackHeatmap?,
        val predictedQuad: FeedbackQuad?,
    )

    var activeSession: SessionInfo? = null
        private set

    fun beginSession(
        sourceFile: File,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceSha256: String,
        status: DetectionStatus,
        confidence: Float?,
        qualityReason: String?,
        maskAreaRatio: Float?,
        heatmap: FeedbackHeatmap?,
        predictedQuad: Quadrilateral?,
    ): SessionInfo? {
        if (!enabled()) return null
        val session = SessionInfo(
            sampleId = "sf-" + UUID.randomUUID().toString().replace("-", ""),
            createdAtUtc = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sourceFile = sourceFile,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceSha256 = sourceSha256,
            status = when (status) {
                DetectionStatus.DETECTED -> FeedbackDetectionStatus.DETECTED
                DetectionStatus.LOW_CONFIDENCE -> FeedbackDetectionStatus.LOW_CONFIDENCE
                DetectionStatus.NOT_FOUND -> FeedbackDetectionStatus.NOT_FOUND
            },
            confidence = confidence,
            qualityReason = qualityReason,
            maskAreaRatio = maskAreaRatio,
            heatmap = heatmap,
            predictedQuad = predictedQuad?.let { FeedbackQuad.from(it) },
        )
        activeSession = session
        return session
    }

    /**
     * 用户点击"下一步"确认四边形（Ground Truth candidate）。
     * 决策通过才打包 source.jpg + metadata.json 到 pending 队列；随后清理 Session。
     * 返回是否已入队。
     */
    fun confirmCorrection(session: SessionInfo?, finalQuad: Quadrilateral): Boolean {
        val s = session ?: activeSession ?: return false
        if (s != activeSession) return false
        activeSession = null
        if (!enabled()) return false

        val corrected = FeedbackQuad.from(finalQuad)
        val outcome = ScannerFeedbackDecision.decide(s.sampleId, s.status, s.predictedQuad, corrected)
            ?: return false

        val queued = writePackage(s, corrected, outcome)
        if (queued) enforceLimits()
        return queued
    }

    /** 用户放弃调整（返回/重拍/使用整张图片）：不产生 GT。 */
    fun cancelSession() {
        activeSession = null
    }

    /** Pending 包列表（目录 + 元数据，按样本目录读取）。 */
    fun pendingPackages(): List<FeedbackPendingPackage> {
        val dirs = pendingDir.listFiles() ?: return emptyList()
        return dirs
            .filter { it.isDirectory && File(it, "metadata.json").exists() && File(it, "source.jpg").exists() }
            .mapNotNull { dir ->
                val reason = runCatching {
                    FeedbackReason.valueOf(
                        JSONObject(File(dir, "metadata.json").readText()).getString("reason"),
                    )
                }.getOrDefault(FeedbackReason.CLEAN_SUCCESS)
                FeedbackPendingPackage(
                    dir = dir,
                    reason = reason,
                    createdAtMillis = dir.lastModified(),
                    totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
    }

    /**
     * 队列上限执行：超过 MAX_PENDING_SAMPLES / MAX_PENDING_BYTES 时按
     * FeedbackQueuePolicy 淘汰（先低价值、再旧），返回删除数。
     */
    fun enforceLimits(): Int {
        val packages = pendingPackages()
        if (packages.size <= ScannerFeedbackConfig.MAX_PENDING_SAMPLES &&
            packages.sumOf { it.totalBytes } <= ScannerFeedbackConfig.MAX_PENDING_BYTES
        ) {
            return 0
        }
        var removed = 0
        var remaining = packages.toMutableList()
        for (p in FeedbackQueuePolicy.evictionOrder(packages)) {
            if (remaining.size <= ScannerFeedbackConfig.MAX_PENDING_SAMPLES &&
                remaining.sumOf { it.totalBytes } <= ScannerFeedbackConfig.MAX_PENDING_BYTES
            ) {
                break
            }
            if (p.dir.deleteRecursively()) {
                remaining.remove(p)
                removed++
            }
        }
        return removed
    }

    data class UploadSummary(val uploaded: Int, val failed: Int)

    /**
     * Best effort 上传全部 pending 样本到已配对电脑（POST /api/v1/scanner-feedback）。
     * 任何 2xx 视为成功并删除本地包；失败保留等待下次重试。绝不向上抛异常。
     */
    fun uploadPending(host: String, port: Int, fingerprint: String, token: String): UploadSummary {
        var uploaded = 0
        var failed = 0
        for (pkg in pendingPackages()) {
            if (uploadPackage(host, port, fingerprint, token, pkg)) {
                if (pkg.dir.deleteRecursively()) uploaded++ else failed++
            } else {
                failed++
            }
        }
        return UploadSummary(uploaded, failed)
    }

    private fun uploadPackage(
        host: String,
        port: Int,
        fingerprint: String,
        token: String,
        pkg: FeedbackPendingPackage,
    ): Boolean {
        return try {
            val client = PinnedClientFactory.create(fingerprint).newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            val metadataFile = File(pkg.dir, "metadata.json")
            val sourceFile = File(pkg.dir, "source.jpg")
            val boundary = "phonelink-fb-" + UUID.randomUUID().toString()
            val body = object : RequestBody() {
                override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()

                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("--$boundary\r\n")
                    sink.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
                    sink.writeUtf8(metadataFile.readText())
                    sink.writeUtf8("\r\n")
                    sink.writeUtf8("--$boundary\r\n")
                    sink.writeUtf8("Content-Disposition: form-data; name=\"file\"; filename=\"source.jpg\"\r\n")
                    sink.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
                    sourceFile.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                        }
                    }
                    sink.writeUtf8("\r\n--$boundary--\r\n")
                }
            }
            val request = Request.Builder()
                .url("https://$host:$port/api/v1/scanner-feedback")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun writePackage(
        session: SessionInfo,
        corrected: FeedbackQuad,
        outcome: ScannerFeedbackDecision.Outcome,
    ): Boolean {
        return try {
            val dir = File(pendingDir, session.sampleId)
            if (dir.exists()) dir.deleteRecursively()
            if (!dir.mkdirs()) return false
            session.sourceFile.copyTo(File(dir, "source.jpg"), overwrite = true)
            val metadata = ScannerFeedbackMetadata(
                sampleId = session.sampleId,
                createdAtUtc = session.createdAtUtc,
                source = FeedbackSource(session.sourceWidth, session.sourceHeight, session.sourceSha256),
                model = FeedbackModel(ScannerFeedbackConfig.MODEL_NAME, ScannerFeedbackConfig.MODEL_SHA256),
                detection = FeedbackDetection(
                    status = session.status,
                    confidence = session.confidence,
                    qualityReason = session.qualityReason,
                    maskAreaRatio = session.maskAreaRatio,
                    heatmap = session.heatmap,
                ),
                predictedQuad = outcome.predictedQuad,
                correctedQuad = corrected,
                correction = outcome.correction,
                reason = outcome.reason,
            )
            File(dir, "metadata.json").writeText(metadata.toJson())
            true
        } catch (_: Throwable) {
            try {
                File(pendingDir, session.sampleId).deleteRecursively()
            } catch (_: Throwable) {
            }
            false
        }
    }
}