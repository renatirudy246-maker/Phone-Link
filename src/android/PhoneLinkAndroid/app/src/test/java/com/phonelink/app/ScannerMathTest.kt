package com.phonelink.app

import com.phonelink.app.scanner.PointF
import com.phonelink.app.scanner.Quadrilateral
import com.phonelink.app.scanner.ScannerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScannerMath 测试：输出尺寸、单应矩阵、映射（纯 Kotlin）。 */
class ScannerMathTest {

    private val eps = 0.001f

    private val square = Quadrilateral(
        PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
        PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
    )

    // ---- perspective output size ----

    @Test
    fun outputSize_rectangleMapsProportionally() {
        val (w, h) = ScannerMath.perspectiveOutputSize(square, 3000, 4000, 6000)
        assertEquals(1800, w)
        assertEquals(2400, h)
    }

    @Test
    fun outputSize_trapezoidUsesMaxSides() {
        // 梯形：顶边 0.2-0.6（0.4），底边 0.2-0.8（0.6）→ 宽取 0.6
        // 左边 (0.2,0.2)-(0.2,0.8) 长 0.6；右边 (0.6,0.2)-(0.8,0.8) 长 √0.4≈0.6325 → 高取 max
        val trap = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.6f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        val (w, h) = ScannerMath.perspectiveOutputSize(trap, 1000, 1000, 6000)
        assertEquals(600, w)
        assertEquals(632, h)
    }

    @Test
    fun outputSize_clampsToMaxEdge() {
        // 全图四边形 1000x400 → 400 长边不超限；用 500 长边限制验证 clamp
        val full = Quadrilateral(
            PointF(0f, 0f), PointF(1f, 0f),
            PointF(1f, 1f), PointF(0f, 1f),
        )
        val (w, h) = ScannerMath.perspectiveOutputSize(full, 4000, 3000, 1000)
        assertEquals(1000, w)
        assertEquals(750, h)
    }

    // ---- homography ----

    @Test
    fun homography_rectangleMapsCornersExactly() {
        val src = listOf(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        val dst = listOf(
            PointF(0f, 0f), PointF(100f, 0f),
            PointF(100f, 100f), PointF(0f, 100f),
        )
        val h = ScannerMath.computeHomography(src, dst)
        assertTrue(h.isFinite)
        for (i in 0 until 4) {
            val mapped = ScannerMath.applyHomography(h, src[i])
            assertEquals(dst[i].x, mapped.x, 0.01f)
            assertEquals(dst[i].y, mapped.y, 0.01f)
        }
    }

    @Test
    fun homography_trapezoidToRectangle() {
        // 梯形源 → 矩形目标（透视校正的核心场景）
        val src = listOf(
            PointF(0.3f, 0.1f), PointF(0.7f, 0.1f),
            PointF(0.9f, 0.9f), PointF(0.1f, 0.9f),
        )
        val dst = listOf(
            PointF(0f, 0f), PointF(100f, 0f),
            PointF(100f, 100f), PointF(0f, 100f),
        )
        val h = ScannerMath.computeHomography(src, dst)
        for (i in 0 until 4) {
            val mapped = ScannerMath.applyHomography(h, src[i])
            assertEquals(dst[i].x, mapped.x, 0.01f)
            assertEquals(dst[i].y, mapped.y, 0.01f)
        }
    }

    @Test
    fun homography_rotatedQuadMapsToRectangle() {
        // 旋转的四边形 → 矩形
        val src = listOf(
            PointF(0.35f, 0.45f), PointF(0.45f, 0.35f),
            PointF(0.65f, 0.55f), PointF(0.55f, 0.65f),
        )
        val dst = listOf(
            PointF(0f, 0f), PointF(100f, 0f),
            PointF(100f, 100f), PointF(0f, 100f),
        )
        val h = ScannerMath.computeHomography(src, dst)
        assertTrue(h.isFinite)
        for (i in 0 until 4) {
            val mapped = ScannerMath.applyHomography(h, src[i])
            assertEquals(dst[i].x, mapped.x, 0.05f)
            assertEquals(dst[i].y, mapped.y, 0.05f)
        }
    }

    // ---- mapping round trips ----

    @Test
    fun normalizedToPixels_quadRoundTrip() {
        val px = ScannerMath.normalizedToPixels(square, 3000, 4000)
        assertEquals(600f, px.topLeft.x, eps)
        assertEquals(800f, px.topLeft.y, eps)
        assertEquals(2400f, px.topRight.x, eps)
        val back = ScannerMath.normalizedToPixels(
            Quadrilateral(
                ScannerMath.pixelsToNormalized(px.topLeft, 3000, 4000),
                ScannerMath.pixelsToNormalized(px.topRight, 3000, 4000),
                ScannerMath.pixelsToNormalized(px.bottomRight, 3000, 4000),
                ScannerMath.pixelsToNormalized(px.bottomLeft, 3000, 4000),
            ),
            3000, 4000,
        )
        assertEquals(px.topLeft.x, back.topLeft.x, 0.1f)
        assertEquals(px.bottomRight.y, back.bottomRight.y, 0.1f)
    }

    @Test
    fun normalizedToPixels_clampsOutOfRange() {
        val out = ScannerMath.normalizedToPixels(
            Quadrilateral(
                PointF(-0.2f, 0.5f), PointF(1.2f, 0.5f),
                PointF(1.2f, 1.5f), PointF(-0.2f, 1.5f),
            ),
            1000, 1000,
        )
        assertEquals(0f, out.topLeft.x, eps)
        assertEquals(1000f, out.topRight.x, eps)
        assertEquals(1000f, out.bottomRight.y, eps)
    }
}