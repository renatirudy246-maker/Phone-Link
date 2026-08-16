package com.phonelink.app.scanner

import android.graphics.Bitmap

/**
 * 结构化耗时度量（用于 DEBUG 调试与性能监控）。
 */
data class DetectionTimings(
    val preprocessMs: Long = 0L,
    val inferenceMs: Long = 0L,
    val postprocessMs: Long = 0L,
    val qualityGateMs: Long = 0L,
    val edgeRefineMs: Long = 0L,
    val totalMs: Long = 0L,
)

/**
 * 分割 Mask 统计指标。
 */
data class MaskStats(
    val probMean: Float = 0f,
    val probGt05Count: Int = 0,
    val foregroundRatio: Float = 0f,
    val centroidX: Float = 0.5f,
    val centroidY: Float = 0.5f,
)

/**
 * 角点热图统计指标。
 */
data class HeatmapStats(
    val peakValues: FloatArray = FloatArray(4),
    val peakMargin: Float = 0f,
    val peakSigma: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HeatmapStats
        return peakValues.contentEquals(other.peakValues) &&
                peakMargin == other.peakMargin &&
                peakSigma == other.peakSigma
    }

    override fun hashCode(): Int {
        var result = peakValues.contentHashCode()
        result = 31 * result + peakMargin.hashCode()
        result = 31 * result + peakSigma.hashCode()
        return result
    }
}

/**
 * 文档检测统一结果封装。
 */
sealed class DocumentDetectionResult {
    abstract val timings: DetectionTimings

    data class Detected(
        val quad: Quadrilateral,
        val confidence: Float,
        val maskStats: MaskStats = MaskStats(),
        val heatmapStats: HeatmapStats = HeatmapStats(),
        val qualityReason: String? = null,
        override val timings: DetectionTimings = DetectionTimings(),
    ) : DocumentDetectionResult()

    data class LowConfidence(
        val quad: Quadrilateral,
        val confidence: Float,
        val maskStats: MaskStats = MaskStats(),
        val heatmapStats: HeatmapStats = HeatmapStats(),
        val qualityReason: String? = null,
        override val timings: DetectionTimings = DetectionTimings(),
    ) : DocumentDetectionResult()

    data class NotFound(
        val defaultQuad: Quadrilateral = Quadrilateral.DEFAULT,
        val reason: String = "未检测到有效文档",
        override val timings: DetectionTimings = DetectionTimings(),
    ) : DocumentDetectionResult()
}

/**
 * 文档检测引擎抽象接口。
 * 允许在不破坏 UI 和后处理流水线的前提下无缝替换检测后端。
 */
interface DocumentDetectionEngine {
    suspend fun detect(image: Bitmap): DocumentDetectionResult
}
