package com.phonelink.app

import com.phonelink.app.scanner.PointF
import com.phonelink.app.scanner.Quadrilateral
import com.phonelink.app.scanner.QuadrilateralMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quadrilateral 模型与几何校验测试（纯 Kotlin）。 */
class QuadrilateralTest {

    private val eps = 0.0001f

    private val square = Quadrilateral(
        PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
        PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
    )

    // ---- ordering ----

    @Test
    fun orderPoints_randomOrderProducesTLTRBRBL() {
        val ordered = QuadrilateralMath.orderPoints(
            listOf(PointF(0.8f, 0.8f), PointF(0.2f, 0.2f), PointF(0.8f, 0.2f), PointF(0.2f, 0.8f))
        )
        assertEquals(0.2f, ordered.topLeft.x, eps)
        assertEquals(0.2f, ordered.topLeft.y, eps)
        assertEquals(0.8f, ordered.topRight.x, eps)
        assertEquals(0.2f, ordered.topRight.y, eps)
        assertEquals(0.8f, ordered.bottomRight.x, eps)
        assertEquals(0.8f, ordered.bottomRight.y, eps)
        assertEquals(0.2f, ordered.bottomLeft.x, eps)
        assertEquals(0.8f, ordered.bottomLeft.y, eps)
    }

    @Test
    fun orderPoints_trapezoidStillOrdered() {
        val ordered = QuadrilateralMath.orderPoints(
            listOf(PointF(0.1f, 0.9f), PointF(0.3f, 0.1f), PointF(0.9f, 0.9f), PointF(0.7f, 0.1f))
        )
        assertEquals(0.3f, ordered.topLeft.x, eps)
        assertEquals(0.7f, ordered.topRight.x, eps)
        assertEquals(0.9f, ordered.bottomRight.x, eps)
        assertEquals(0.1f, ordered.bottomLeft.x, eps)
    }

    @Test
    fun orderPoints_tiltedQuadKeepsOrder() {
        // 旋转 30 度的方形（绕中心），sum/diff 法仍应给出 TL/TR/BR/BL 顺序
        val cx = 0.5f; val cy = 0.5f
        val r = 0.3f
        fun rot(angle: Double) = PointF(
            cx + r * kotlin.math.cos(angle).toFloat(),
            cy + r * kotlin.math.sin(angle).toFloat(),
        )
        // 顺时针: TL=315°, TR=45°, BR=135°, BL=225°
        val points = listOf(rot(Math.toRadians(135.0)), rot(Math.toRadians(315.0)), rot(Math.toRadians(45.0)), rot(Math.toRadians(225.0)))
        val ordered = QuadrilateralMath.orderPoints(points)
        assertTrue(ordered.topLeft.x <= 0.5f && ordered.topLeft.y <= 0.5f)
        assertTrue(ordered.bottomRight.x >= 0.5f && ordered.bottomRight.y >= 0.5f)
        assertTrue(ordered.topRight.x >= 0.5f && ordered.topRight.y <= 0.5f)
        assertTrue(ordered.bottomLeft.x <= 0.5f && ordered.bottomLeft.y >= 0.5f)
    }

    // ---- convex ----

    @Test
    fun isConvex_rectangleIsConvex() {
        assertTrue(QuadrilateralMath.isConvex(square))
    }

    @Test
    fun isConvex_concaveBowIsNotConvex() {
        val concave = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
            PointF(0.5f, 0.8f), PointF(0.4f, 0.3f),
        )
        assertFalse(QuadrilateralMath.isConvex(concave))
    }

    // ---- self intersection ----

    @Test
    fun isSelfIntersecting_bowTieIsDetected() {
        val bowTie = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.8f),
            PointF(0.8f, 0.2f), PointF(0.2f, 0.8f),
        )
        assertTrue(QuadrilateralMath.isSelfIntersecting(bowTie))
    }

    @Test
    fun isSelfIntersecting_rectangleIsNot() {
        assertFalse(QuadrilateralMath.isSelfIntersecting(square))
    }

    // ---- area / min area ----

    @Test
    fun area_rectangleIsProduct() {
        assertEquals(0.36f, QuadrilateralMath.area(square), 0.001f)
    }

    @Test
    fun hasMinimumArea_smallQuadRejected() {
        val tiny = Quadrilateral(
            PointF(0.5f, 0.5f), PointF(0.51f, 0.5f),
            PointF(0.51f, 0.51f), PointF(0.5f, 0.51f),
        )
        assertFalse(QuadrilateralMath.hasMinimumArea(tiny, 0.02f))
        assertTrue(QuadrilateralMath.hasMinimumArea(square, 0.02f))
    }

    // ---- validity ----

    @Test
    fun isValid_orderedRectanglePasses() {
        assertTrue(QuadrilateralMath.isValid(square))
    }

    @Test
    fun isValid_selfIntersectingFails() {
        val bowTie = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.8f),
            PointF(0.8f, 0.2f), PointF(0.2f, 0.8f),
        )
        assertFalse(QuadrilateralMath.isValid(bowTie))
    }

    @Test
    fun isValid_duplicatePointsFails() {
        val dup = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.2f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        assertFalse(QuadrilateralMath.isValid(dup))
    }

    // ---- clamp ----

    @Test
    fun clampToBounds_clampsAllPoints() {
        val out = QuadrilateralMath.clampToBounds(
            Quadrilateral(
                PointF(-0.3f, 1.4f), PointF(1.2f, -0.1f),
                PointF(1.5f, 1.6f), PointF(0.0f, 0.9f),
            )
        )
        assertEquals(0f, out.topLeft.x, eps)
        assertEquals(1f, out.topLeft.y, eps)
        assertEquals(1f, out.topRight.x, eps)
        assertEquals(0f, out.topRight.y, eps)
        assertEquals(1f, out.bottomRight.x, eps)
        assertEquals(1f, out.bottomRight.y, eps)
    }

    // ---- aspect ----

    @Test
    fun aspectRatio_squareIsOne() {
        assertEquals(1f, QuadrilateralMath.aspectRatio(square), 0.001f)
    }

    @Test
    fun aspectRatio_wideRectangleIsSmall() {
        val wide = Quadrilateral(
            PointF(0.0f, 0.45f), PointF(1f, 0.45f),
            PointF(1f, 0.55f), PointF(0.0f, 0.55f),
        )
        assertEquals(0.1f, QuadrilateralMath.aspectRatio(wide), 0.001f)
    }

    @Test
    fun defaultQuadIsValid() {
        assertTrue(QuadrilateralMath.isValid(Quadrilateral.DEFAULT))
        assertEquals(0.81f, QuadrilateralMath.area(Quadrilateral.DEFAULT), 0.001f)
    }
}