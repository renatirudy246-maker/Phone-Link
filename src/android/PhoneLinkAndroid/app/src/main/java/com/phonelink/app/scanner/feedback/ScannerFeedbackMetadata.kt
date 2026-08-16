package com.phonelink.app.scanner.feedback

import com.phonelink.app.scanner.PointF
import com.phonelink.app.scanner.Quadrilateral
import org.json.JSONArray
import org.json.JSONObject

/** 采集原因（样本价值分类）。 */
enum class FeedbackReason { USER_CORRECTED, LOW_CONFIDENCE, MODEL_NOT_FOUND, CLEAN_SUCCESS }

/** 检测状态（序列化大小写与 Schema V1 样例一致）。 */
enum class FeedbackDetectionStatus(val wire: String) {
    DETECTED("Detected"),
    LOW_CONFIDENCE("LowConfidence"),
    NOT_FOUND("NotFound");
}

/** 四边形（归一化 0.0..1.0 图像坐标，TL/TR/BR/BL 顺时针）。 */
data class FeedbackQuad(
    val tl: PointF,
    val tr: PointF,
    val br: PointF,
    val bl: PointF,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tl", pointArray(tl))
        .put("tr", pointArray(tr))
        .put("br", pointArray(br))
        .put("bl", pointArray(bl))

    private fun pointArray(p: PointF): JSONArray =
        JSONArray().put(p.x.toDouble()).put(p.y.toDouble())

    companion object {
        fun from(quad: Quadrilateral): FeedbackQuad =
            FeedbackQuad(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
    }
}

data class FeedbackSource(val width: Int, val height: Int, val sha256: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("width", width)
        .put("height", height)
        .put("sha256", sha256)
}

data class FeedbackModel(val name: String, val sha256: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("sha256", sha256)
}

data class FeedbackHeatmap(val peakSigma: Float, val peakMargin: Float, val peakValues: List<Float>) {
    fun toJson(): JSONObject = JSONObject()
        .put("peakSigma", peakSigma.toDouble())
        .put("peakMargin", peakMargin.toDouble())
        .put("peakValues", JSONArray(peakValues.map { it.toDouble() }))
}

data class FeedbackDetection(
    val status: FeedbackDetectionStatus,
    val confidence: Float?,
    val qualityReason: String?,
    val maskAreaRatio: Float?,
    val heatmap: FeedbackHeatmap?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status.wire)
        .put("confidence", confidence?.toDouble() ?: JSONObject.NULL)
        .put("qualityReason", qualityReason ?: JSONObject.NULL)
        .put("maskAreaRatio", maskAreaRatio?.toDouble() ?: JSONObject.NULL)
        .put("heatmap", heatmap?.toJson() ?: JSONObject.NULL)
}

data class FeedbackCorrection(
    val meanDelta: Float,
    val maxDelta: Float,
    val adjustedCorners: List<String>,
    val predictionMissing: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("meanDelta", meanDelta.toDouble())
        .put("maxDelta", maxDelta.toDouble())
        .put("adjustedCorners", JSONArray(adjustedCorners))
        .put("predictionMissing", predictionMissing)
}

/**
 * Scanner Feedback Metadata Schema V1。
 * detector source = EXIF 方向归一化后的 prepared 原图（perspective warp 之前）。
 */
data class ScannerFeedbackMetadata(
    val schemaVersion: Int = ScannerFeedbackConfig.SCHEMA_VERSION,
    val sampleId: String,
    val createdAtUtc: String,
    val labelSource: String = ScannerFeedbackConfig.LABEL_SOURCE,
    val source: FeedbackSource,
    val model: FeedbackModel,
    val detection: FeedbackDetection,
    val predictedQuad: FeedbackQuad?,
    val correctedQuad: FeedbackQuad,
    val correction: FeedbackCorrection,
    val reason: FeedbackReason,
) {
    fun toJson(): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("sampleId", sampleId)
        .put("createdAtUtc", createdAtUtc)
        .put("labelSource", labelSource)
        .put("source", source.toJson())
        .put("model", model.toJson())
        .put("detection", detection.toJson())
        .put("predictedQuad", predictedQuad?.toJson() ?: JSONObject.NULL)
        .put("correctedQuad", correctedQuad.toJson())
        .put("correction", correction.toJson())
        .put("reason", reason.name)
        .toString()
}