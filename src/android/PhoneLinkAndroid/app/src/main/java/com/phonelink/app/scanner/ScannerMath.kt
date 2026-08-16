package com.phonelink.app.scanner

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 扫描相关数学（纯 Kotlin，无 Android/OpenCV 依赖，可 JVM 单测）。
 * 坐标约定：归一化 0..1 相对方向归一化后的图片；像素坐标为原始分辨率。
 */
object ScannerMath {

    data class Homography(val h: FloatArray) {
        val isFinite: Boolean get() = h.all { it.isFinite() }
    }

    /** 归一化四边形 → 图片像素点。 */
    fun normalizedToPixels(p: PointF, imageWidth: Int, imageHeight: Int): PointF =
        PointF(p.x * imageWidth, p.y * imageHeight)

    /** 图片像素点 → 归一化。 */
    fun pixelsToNormalized(p: PointF, imageWidth: Int, imageHeight: Int): PointF =
        PointF(p.x / imageWidth, p.y / imageHeight)

    /** 四边形归一化 → 像素（clamp 到图片范围）。 */
    fun normalizedToPixels(quad: Quadrilateral, imageWidth: Int, imageHeight: Int): Quadrilateral {
        fun c(p: PointF) = PointF(
            (p.x * imageWidth).coerceIn(0f, imageWidth.toFloat()),
            (p.y * imageHeight).coerceIn(0f, imageHeight.toFloat()),
        )
        return Quadrilateral(c(quad.topLeft), c(quad.topRight), c(quad.bottomRight), c(quad.bottomLeft))
    }

    /**
     * 透视校正输出尺寸：
     * 输出宽 = max(上边, 下边) 像素长；输出高 = max(左边, 右边)；
     * 最长边夹紧到 maxEdge（保持比例，取整）。
     */
    fun perspectiveOutputSize(quad: Quadrilateral, imageWidth: Int, imageHeight: Int, maxEdge: Int): Pair<Int, Int> {
        val px = normalizedToPixels(quad, imageWidth, imageHeight)
        fun dist(a: PointF, b: PointF): Float =
            kotlin.math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
        val topWidth = dist(px.topLeft, px.topRight)
        val bottomWidth = dist(px.bottomLeft, px.bottomRight)
        val leftHeight = dist(px.topLeft, px.bottomLeft)
        val rightHeight = dist(px.topRight, px.bottomRight)
        var outWidth = max(topWidth, bottomWidth)
        var outHeight = max(leftHeight, rightHeight)
        val longest = max(outWidth, outHeight)
        if (longest > maxEdge) {
            val scale = maxEdge / longest
            outWidth *= scale
            outHeight *= scale
        }
        val w = outWidth.roundToInt().coerceAtLeast(2)
        val h = outHeight.roundToInt().coerceAtLeast(2)
        return w to h
    }

    /**
     * 8 参数单应矩阵（src → dst，dst 为矩形目标角点）。
     * 求解 8 元线性方程组（DLT 的解析版，用于单元测试与验证；生产使用 OpenCV getPerspectiveTransform）。
     */
    fun computeHomography(src: List<PointF>, dst: List<PointF>): Homography {
        require(src.size == 4 && dst.size == 4) { "need 4 point pairs" }
        // h = [h11 h12 h13 h21 h22 h23 h31 h32 h33], h33 = 1
        // 每对点贡献两行：
        //   x' = (h11*x + h12*y + h13) / (h31*x + h32*y + 1)
        //   y' = (h21*x + h22*y + h23) / (h31*x + h32*y + 1)
        val a = Array(8) { FloatArray(8) }
        val b = FloatArray(8)
        for (i in 0 until 4) {
            val (x, y) = src[i]
            val (xp, yp) = dst[i]
            a[i * 2][0] = x; a[i * 2][1] = y; a[i * 2][2] = 1f
            a[i * 2][3] = 0f; a[i * 2][4] = 0f; a[i * 2][5] = 0f
            a[i * 2][6] = -xp * x; a[i * 2][7] = -xp * y
            b[i * 2] = xp
            a[i * 2 + 1][0] = 0f; a[i * 2 + 1][1] = 0f; a[i * 2 + 1][2] = 0f
            a[i * 2 + 1][3] = x; a[i * 2 + 1][4] = y; a[i * 2 + 1][5] = 1f
            a[i * 2 + 1][6] = -yp * x; a[i * 2 + 1][7] = -yp * y
            b[i * 2 + 1] = yp
        }
        val h8 = solveLinear(a, b)
        return Homography(floatArrayOf(h8[0], h8[1], h8[2], h8[3], h8[4], h8[5], h8[6], h8[7], 1f))
    }

    /** 高斯消元（列主元）解 8x8。 */
    private fun solveLinear(a: Array<FloatArray>, b: FloatArray): FloatArray {
        val n = 8
        val m = Array(n) { a[it].copyOf() }
        val rhs = b.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            }
            if (abs(m[pivot][col]) < 1e-10f) throw IllegalArgumentException("singular matrix")
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val tb = rhs[col]; rhs[col] = rhs[pivot]; rhs[pivot] = tb
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col] / m[col][col]
                if (factor == 0f) continue
                for (k in col until n) m[row][k] -= factor * m[col][k]
                rhs[row] -= factor * rhs[col]
            }
        }
        return FloatArray(n) { rhs[it] / m[it][it] }
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v

    /** 用单应矩阵映射一个点（齐次除法）。 */
    fun applyHomography(h: Homography, p: PointF): PointF {
        val x = h.h[0] * p.x + h.h[1] * p.y + h.h[2]
        val y = h.h[3] * p.x + h.h[4] * p.y + h.h[5]
        val w = h.h[6] * p.x + h.h[7] * p.y + h.h[8]
        return PointF(x / w, y / w)
    }
}