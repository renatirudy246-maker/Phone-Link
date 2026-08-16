package com.phonelink.app.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * 文档边缘检测（OpenCV，本地执行）：
 * downscale → grayscale → CLAHE 对比度 → Gaussian blur → Canny → morphology close →
 * findContours → approxPolyDP → 凸四边形筛选 → 评分 → best candidate。
 */
object DocumentDetector {

    /** 拍摄后高质量检测：对方向归一化的完整图片检测。 */
    fun detectHighQuality(bitmap: Bitmap): DocumentDetectionResult {
        val scaled = downscale(bitmap, ScannerConfig.HIGH_QUALITY_MAX_EDGE)
        val result = runDetection(scaled).result
        // 等比缩放下归一化坐标与全图一致，无需换算
        if (scaled !== bitmap) scaled.recycle()
        return result
    }

    /** 实时检测：对 CameraX 分析帧检测（小图，节流由调用方控制）。 */
    fun detectLive(bitmap: Bitmap): DocumentDetectionResult {
        val scaled = downscale(bitmap, ScannerConfig.LIVE_MAX_EDGE)
        val result = runDetection(scaled).result
        if (scaled !== bitmap) scaled.recycle()
        return result
    }

    private data class DetectionOutcome(
        val result: DocumentDetectionResult,
    )

    /** 居中等比缩放到目标长边；返回 scale 与 letterbox 偏移。 */
    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun runDetection(bitmap: Bitmap): DetectionOutcome {
        val started = System.currentTimeMillis()
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGRA2GRAY)

            // 对比度归一化（CLAHE，避免光照不均导致边缘断裂）
            val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
            val equalized = Mat()
            clahe.apply(gray, equalized)

            // 降噪
            val blurred = Mat()
            Imgproc.GaussianBlur(equalized, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)

            // 边缘
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            // 形态学闭运算：连接断裂边缘
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

            // 轮廓
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageWidth = bitmap.width.toFloat()
            val imageHeight = bitmap.height.toFloat()
            val imageArea = imageWidth * imageHeight

            var best: Pair<Quadrilateral, Float>? = null
            var bestScore = 0f

            for (contour in contours) {
                val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                if (perimeter <= 0f) continue
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * perimeter, true)
                val count = approx.total()
                if (count != 4L) continue

                val points = approx.toArray()
                val quad = QuadrilateralMath.orderPoints(
                    points.map { PointF(it.x.toFloat() / imageWidth, it.y.toFloat() / imageHeight) }
                )
                if (!QuadrilateralMath.isValid(quad, ScannerConfig.MIN_AREA_FRACTION)) continue

                val score = scoreCandidate(quad, imageWidth, imageHeight, imageArea)
                if (score > bestScore) {
                    bestScore = score
                    best = quad to score
                }
                approx.release()
            }

            return if (best == null) {
                recordDetectMs(System.currentTimeMillis() - started)
                DetectionOutcome(DocumentDetectionResult.NotFound)
            } else {
                val confidence = bestScore.coerceIn(0f, 1f)
                val quad = best!!.first
                recordDetectMs(System.currentTimeMillis() - started)
                val result = when {
                    confidence >= ScannerConfig.CONFIDENCE_DETECTED ->
                        DocumentDetectionResult.Detected(quad, confidence)
                    confidence >= ScannerConfig.CONFIDENCE_LOW ->
                        DocumentDetectionResult.LowConfidence(quad, confidence)
                    else -> DocumentDetectionResult.NotFound
                }
                DetectionOutcome(result)
            }
        } finally {
            src.release()
        }
    }

    /**
     * 候选评分（0..1）：面积占比、中心接近度、宽高比、边框惩罚、角度合理性。
     * 不使用"最大面积即文档"的简单策略。
     */
    private fun scoreCandidate(quad: Quadrilateral, imageWidth: Float, imageHeight: Float, imageArea: Float): Float {
        val quadArea = QuadrilateralMath.area(quad) * imageArea

        // 1. 面积占比（0.2–0.98 之间给高分；太小或贴满全图都降分）
        val areaRatio = quadArea / imageArea
        val areaScore = when {
            areaRatio < 0.1f -> areaRatio / 0.1f * 0.5f
            areaRatio <= 0.95f -> 1f
            else -> (1f - (areaRatio - 0.95f) / 0.05f * 0.8f).coerceIn(0f, 1f)
        }

        // 2. 中心接近度
        val center = QuadrilateralMath.center(quad)
        val centerDist = abs(center.x - 0.5f) + abs(center.y - 0.5f)
        val centerScore = (1f - centerDist).coerceIn(0f, 1f)

        // 3. 宽高比合理性（0.25–1 之间好；细长条降分）
        val aspect = QuadrilateralMath.aspectRatio(quad)
        val aspectScore = when {
            aspect >= 0.25f -> 1f
            else -> (aspect / 0.25f).coerceIn(0f, 1f)
        }

        // 4. 边框惩罚：贴近图片边缘的点（<2%）降分
        var borderPenalty = 0f
        for (p in quad.points) {
            if (p.x < 0.02f || p.x > 0.98f || p.y < 0.02f || p.y > 0.98f) borderPenalty += 0.2f
        }
        val borderScore = (1f - borderPenalty).coerceIn(0f, 1f)

        // 5. 角度合理性：四角接近 90°（点积归一化）
        val angleScore = anglePlausibility(quad)

        return areaScore * 0.35f + centerScore * 0.25f + aspectScore * 0.2f + borderScore * 0.1f + angleScore * 0.1f
    }

    private fun anglePlausibility(quad: Quadrilateral): Float {
        val p = quad.points
        var total = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val v1x = b.x - a.x
            val v1y = b.y - a.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val len1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
            val len2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)
            if (len1 <= 0f || len2 <= 0f) continue
            val dot = (v1x * v2x + v1y * v2y) / (len1 * len2)
            // 90° → dot≈0 → 得分 1；0°/180° → dot≈±1 → 得分 0
            val score = (1f - abs(dot)).coerceIn(0f, 1f)
            total += score
        }
        return total / 4f
    }

    /** 调试信息：最近一次检测的耗时（ms），默认关闭 UI 展示。 */
    @Volatile
    var lastDetectMs: Long = 0
        private set

    internal fun recordDetectMs(ms: Long) {
        lastDetectMs = ms
    }
}