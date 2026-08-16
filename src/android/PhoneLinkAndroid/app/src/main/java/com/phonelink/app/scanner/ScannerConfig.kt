package com.phonelink.app.scanner

/** 扫描管线配置（固定常量，可在真机实测后调整）。 */
object ScannerConfig {
    /** 实时检测（Camera ImageAnalysis）最长边。 */
    const val LIVE_MAX_EDGE = 720

    /** 拍摄后高质量检测最长边。 */
    const val HIGH_QUALITY_MAX_EDGE = 2048

    /** 透视校正输出最长边上限（与 Phase 3 图片策略一致，4096 内）。 */
    const val WARP_MAX_EDGE = 4096

    /** 候选最小面积（相对检测图）。 */
    const val MIN_AREA_FRACTION = 0.12f

    /** Detected 置信度阈值。 */
    const val CONFIDENCE_DETECTED = 0.55f

    /** LowConfidence 置信度阈值（低于此 → NotFound）。 */
    const val CONFIDENCE_LOW = 0.30f

    /** 实时检测节流：两次检测最小间隔（ms）。 */
    const val LIVE_DETECT_INTERVAL_MS = 150L
}

/** 检测状态（UI 提示用）。 */
enum class DetectionStatus { DETECTED, LOW_CONFIDENCE, NOT_FOUND }

/** 检测结果。 */
sealed interface DocumentDetectionResult {
    data class Detected(val quad: Quadrilateral, val confidence: Float) : DocumentDetectionResult
    data class LowConfidence(val quad: Quadrilateral, val confidence: Float) : DocumentDetectionResult
    data object NotFound : DocumentDetectionResult
}