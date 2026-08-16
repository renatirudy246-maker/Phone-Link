package com.phonelink.app.scanner.feedback

import kotlin.math.sqrt

/**
 * 纠错度量数学（纯 Kotlin，JVM 单测）。
 * 四角误差 = 预测与确认坐标的 Euclidean distance / 归一化对角线 sqrt(2)。
 */
object ScannerFeedbackMath {

    private const val NORMALIZED_DIAGONAL = 1.41421356f // sqrt(2)

    /** 四角误差（TL/TR/BR/BL 顺序，已按归一化对角线归一化）。 */
    fun cornerDeltas(predicted: FeedbackQuad, corrected: FeedbackQuad): FloatArray {
        val ps = listOf(predicted.tl, predicted.tr, predicted.br, predicted.bl)
        val cs = listOf(corrected.tl, corrected.tr, corrected.br, corrected.bl)
        return FloatArray(4) { i ->
            val dx = ps[i].x - cs[i].x
            val dy = ps[i].y - cs[i].y
            sqrt(dx * dx + dy * dy) / NORMALIZED_DIAGONAL
        }
    }

    /** 被调整的角（delta > epsilon 的角名，顺序 TL/TR/BR/BL）。 */
    fun adjustedCorners(deltas: FloatArray, epsilon: Float): List<String> {
        val names = listOf("TL", "TR", "BR", "BL")
        return names.filterIndexed { i, _ -> deltas[i] > epsilon }
    }

    fun mean(deltas: FloatArray): Float = if (deltas.isEmpty()) 0f else deltas.sum() / deltas.size

    fun max(deltas: FloatArray): Float = deltas.maxOrNull() ?: 0f

    /**
     * CLEAN_SUCCESS 确定性采样：sampleId 尾部 8 位 hex → % 100 < ratePercent。
     * 同一 session（同一 sampleId）永远得到同一结论，重试不会改变采样状态。
     */
    fun shouldSampleCleanSuccess(sampleId: String, ratePercent: Int): Boolean {
        if (ratePercent <= 0) return false
        if (ratePercent >= 100) return true
        val value = sampleId.takeLast(8).toLongOrNull(16)
            ?: (sampleId.hashCode().toLong() and 0xFFFF)
        return value % 100 < ratePercent
    }
}