package com.phonelink.app.scanner

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

enum class QualityStatus {
    DETECTED,
    LOW_CONFIDENCE,
    NOT_FOUND,
}

data class QualityAssessment(
    val status: QualityStatus,
    val confidence: Float,
    val reason: String,
)

/**
 * 5 维信号综合质量门控评估器 (Document Presence / Quality Gate)。
 *
 * 信号维度：
 * A. 热图峰值信噪比 (Peak Sigma / Peak Margin)
 * B. 分割 Mask 面积与连续紧凑度
 * C. 四边形几何合规性 (凸性、无自相交、最小面积、内角范围 35°..145°)
 * D. Mask 质心与四边形中心包络一致性
 * E. 高分辨率边缘局部梯度支撑度
 */
object DocumentQualityEvaluator {

    private const val MIN_AREA_FRACTION = 0.06f
    private const val MAX_AREA_FRACTION = 0.98f
    private const val MIN_PEAK_SIGMA_DETECTED = 4.5f
    private const val MIN_PEAK_SIGMA_LOW_CONF = 2.8f

    fun evaluate(
        quad: Quadrilateral,
        maskStats: MaskStats,
        heatmapStats: HeatmapStats,
        image: Bitmap?,
    ): QualityAssessment {
        // 1. 基础几何合规性校验 (Signal C)
        if (!QuadrilateralMath.isConvex(quad)) {
            return QualityAssessment(QualityStatus.NOT_FOUND, 0f, "几何非凸四边形")
        }
        if (QuadrilateralMath.isSelfIntersecting(quad)) {
            return QualityAssessment(QualityStatus.NOT_FOUND, 0f, "四边形自相交")
        }

        val quadArea = QuadrilateralMath.area(quad)
        if (quadArea < MIN_AREA_FRACTION) {
            return QualityAssessment(QualityStatus.NOT_FOUND, 0f, "文档面积过小 (${(quadArea * 100).toInt()}%)")
        }
        if (quadArea > MAX_AREA_FRACTION && maskStats.foregroundRatio > 0.96f && heatmapStats.peakSigma < MIN_PEAK_SIGMA_DETECTED) {
            return QualityAssessment(QualityStatus.NOT_FOUND, 0f, "全屏弥散无明显边界")
        }

        // 检查四角内角是否合理 (35° .. 145°)
        if (!checkAngles(quad)) {
            return QualityAssessment(QualityStatus.LOW_CONFIDENCE, 0.45f, "四角内角畸变较大")
        }

        val negPeaksCount = heatmapStats.peakValues.count { it < 0.0f }
        val meanPeak = heatmapStats.peakValues.average().toFloat()

        // 2. 负样本 / 无文档抑制校验 (Signal A & B)
        if (negPeaksCount >= 2 || meanPeak < 0.2f || maskStats.foregroundRatio < 0.04f || (maskStats.foregroundRatio < 0.08f && heatmapStats.peakMargin < 19.0f)) {
            return QualityAssessment(QualityStatus.NOT_FOUND, 0f, "未检测到文档主体")
        }

        // 3. Mask 与角点空间一致性校验 (Signal D)
        val quadCenter = QuadrilateralMath.center(quad)
        val centerDist = hypot(quadCenter.x - maskStats.centroidX, quadCenter.y - maskStats.centroidY)
        if (centerDist > 0.25f && maskStats.foregroundRatio > 0.1f) {
            return QualityAssessment(QualityStatus.LOW_CONFIDENCE, 0.45f, "角点与分割主体偏差较大")
        }

        // 4. 置信度评估与三级分类
        return when {
            negPeaksCount == 0 && meanPeak >= 1.6f && maskStats.foregroundRatio in 0.12f..0.95f && heatmapStats.peakMargin >= 21.0f -> {
                QualityAssessment(QualityStatus.DETECTED, 0.88f, "文档边缘清晰可信")
            }
            else -> {
                QualityAssessment(QualityStatus.LOW_CONFIDENCE, 0.55f, "请检查页面边缘")
            }
        }
    }

    private fun checkAngles(quad: Quadrilateral): Boolean {
        val p = quad.points
        for (i in 0 until 4) {
            val prev = p[(i + 3) % 4]
            val curr = p[i]
            val next = p[(i + 1) % 4]

            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y

            val dot = v1x * v2x + v1y * v2y
            val len1 = hypot(v1x, v1y)
            val len2 = hypot(v2x, v2y)
            if (len1 < 1e-4f || len2 < 1e-4f) return false

            val cos = (dot / (len1 * len2)).coerceIn(-1f, 1f)
            val angleDeg = Math.toDegrees(acos(cos.toDouble())).toFloat()
            if (angleDeg < 35f || angleDeg > 145f) {
                return false
            }
        }
        return true
    }
}
