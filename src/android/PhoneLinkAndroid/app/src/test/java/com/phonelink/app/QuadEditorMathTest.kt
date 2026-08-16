package com.phonelink.app

import com.phonelink.app.scanner.ImageRectF
import com.phonelink.app.scanner.PointF
import com.phonelink.app.scanner.QuadEditorMath
import com.phonelink.app.scanner.Quadrilateral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corner Drag 坐标映射测试（用户指定用例 + letterbox/portrait/landscape/edge clamp/
 * corner identity/invalid quad reject）。
 */
class QuadEditorMathTest {

    private val eps = 1e-4f

    // 用户用例：ImageRect L=100 T=200 W=800 H=1000
    private val rect = ImageRectF(left = 100f, top = 200f, right = 900f, bottom = 1200f)

    @Test
    fun pointerToNormalized_centerMapsExactly() {
        val n = QuadEditorMath.pointerToNormalized(PointF(500f, 700f), rect)
        assertEquals(0.5f, n.x, eps)
        assertEquals(0.5f, n.y, eps)
    }

    @Test
    fun pointerToNormalized_smallDeltaIsProportional() {
        // pointer x +8px → normalized 只增 8/800 = 0.01，不能大跳
        val n = QuadEditorMath.pointerToNormalized(PointF(508f, 700f), rect)
        assertEquals(0.51f, n.x, eps)
        assertEquals(0.5f, n.y, eps)
    }

    @Test
    fun pointerToNormalized_tenPxDeltaOnWideRect() {
        // 显示图宽 1600：10px → 0.00625
        val wide = ImageRectF(0f, 0f, 1600f, 1000f)
        val n = QuadEditorMath.pointerToNormalized(PointF(10f, 500f), wide)
        assertEquals(0.00625f, n.x, 1e-5f)
        assertEquals(0.5f, n.y, eps)
    }

    @Test
    fun pointerToNormalized_letterboxLeftClamp() {
        // 指针在图片显示区左边（letterbox 外）→ clamp 0
        val n = QuadEditorMath.pointerToNormalized(PointF(50f, 700f), rect)
        assertEquals(0f, n.x, eps)
        assertEquals(0.5f, n.y, eps)
    }

    @Test
    fun pointerToNormalized_letterboxRightBottomClamp() {
        val n = QuadEditorMath.pointerToNormalized(PointF(950f, 1300f), rect)
        assertEquals(1f, n.x, eps)
        assertEquals(1f, n.y, eps)
    }

    @Test
    fun pointerToNormalized_landscapeRect() {
        // 横图：rect 宽 > 高
        val landscape = ImageRectF(0f, 100f, 1080f, 700f)
        val n = QuadEditorMath.pointerToNormalized(PointF(540f, 400f), landscape)
        assertEquals(0.5f, n.x, eps)
        assertEquals(0.5f, n.y, eps)
    }

    @Test
    fun pointerToNormalized_portraitRect() {
        // 竖图：rect 高 > 宽
        val portrait = ImageRectF(50f, 0f, 650f, 1200f)
        val n = QuadEditorMath.pointerToNormalized(PointF(350f, 600f), portrait)
        assertEquals(0.5f, n.x, eps)
        assertEquals(0.5f, n.y, eps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pointerToNormalized_zeroSizeRectThrows() {
        QuadEditorMath.pointerToNormalized(PointF(0f, 0f), ImageRectF(0f, 0f, 0f, 0f))
    }

    @Test
    fun applyCornerDrag_movesOnlyTargetCorner() {
        val quad = Quadrilateral(
            PointF(0.1f, 0.1f), PointF(0.9f, 0.1f),
            PointF(0.9f, 0.9f), PointF(0.1f, 0.9f),
        )
        val updated = QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.2f, 0.3f))!!
        // TL 更新，其余三个角不变（semantic identity 保持）
        assertEquals(0.2f, updated.topLeft.x, eps)
        assertEquals(0.3f, updated.topLeft.y, eps)
        assertEquals(quad.topRight, updated.topRight)
        assertEquals(quad.bottomRight, updated.bottomRight)
        assertEquals(quad.bottomLeft, updated.bottomLeft)
    }

    @Test
    fun applyCornerDrag_identityNotReorderedAfterDrag() {
        // 把 TL 拖到接近 TR 的位置，角身份不得交换
        val quad = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        val updated = QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.75f, 0.15f))!!
        assertEquals(0.75f, updated.topLeft.x, eps)
        assertEquals(0.15f, updated.topLeft.y, eps)
        assertEquals(0.8f, updated.topRight.x, eps)
    }

    @Test
    fun applyCornerDrag_rejectsSelfIntersection() {
        // TL 拖过对角线 → 自交 → 返回 null（保持上一合法位置）
        val quad = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        assertNull(QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.5f, 0.85f)))
    }

    @Test
    fun applyCornerDrag_rejectsDegenerateArea() {
        val quad = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        // TL 与 TR 几乎重合（<1e-4）→ 点重合校验拒绝
        assertNull(QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.79995f, 0.2f)))
    }

    @Test
    fun applyCornerDrag_rejectsNonConvex() {
        // BR 拖进多边形内部（靠近 TL）→ 叉积符号翻转 → 非凸 → 拒绝
        val quad = Quadrilateral(
            PointF(0.2f, 0.2f), PointF(0.8f, 0.2f),
            PointF(0.8f, 0.8f), PointF(0.2f, 0.8f),
        )
        assertNull(QuadEditorMath.applyCornerDrag(quad, 2, PointF(0.25f, 0.25f)))
    }

    @Test
    fun applyCornerDrag_minAreaFractionApplied() {
        // 起始薄四边形 0.2×0.12（面积 0.024，float 安全余量）；TL 内收 → 面积 0.0192 <0.02
        val quad = Quadrilateral(
            PointF(0.4f, 0.44f), PointF(0.6f, 0.44f),
            PointF(0.6f, 0.56f), PointF(0.4f, 0.56f),
        )
        assertNull(QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.48f, 0.44f)))
        // 放宽 minArea 后允许（仍凸、无自交）
        assertNotNull(QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.48f, 0.44f), minAreaFraction = 1e-4f))
        // 面积 ≥ 0.02 的移动应被允许
        assertNotNull(QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.4f, 0.44f)))
    }

    @Test
    fun cornerName_mapping() {
        assertEquals("TL", QuadEditorMath.cornerName(0))
        assertEquals("TR", QuadEditorMath.cornerName(1))
        assertEquals("BR", QuadEditorMath.cornerName(2))
        assertEquals("BL", QuadEditorMath.cornerName(3))
    }

    @Test
    fun pointerToNormalized_pixelPrecisionNoRoundingJumps() {
        // 逐像素移动（1px）时 normalized 单调小步进，无跳变
        var prev = QuadEditorMath.pointerToNormalized(PointF(500f, 700f), rect).x
        for (i in 1..10) {
            val cur = QuadEditorMath.pointerToNormalized(PointF(500f + i, 700f), rect).x
            assertTrue(cur - prev in 0.001f..0.002f)
            prev = cur
        }
    }

    @Test
    fun gestureSafeInsets_editorViewportMapsEdgesCorrectly() {
        // 模拟物理屏幕 1080x2400，安全边距左右各 48px，有效编辑器视口 984x2000
        // 原始图片 3000x4000 (3:4 竖图)
        val fit = com.phonelink.app.crop.CropMath.fitRect(984f, 2000f, 3000f, 4000f)
        val safeRect = ImageRectF(fit.left, fit.top, fit.right, fit.bottom)

        // 1. 指针在图片左边界 (safeRect.left) -> 归一化 X == 0.0f
        val leftNorm = QuadEditorMath.pointerToNormalized(PointF(safeRect.left, safeRect.top + 200f), safeRect)
        assertEquals(0.0f, leftNorm.x, eps)

        // 2. 指针在图片右边界 (safeRect.right) -> 归一化 X == 1.0f
        val rightNorm = QuadEditorMath.pointerToNormalized(PointF(safeRect.right, safeRect.top + 200f), safeRect)
        assertEquals(1.0f, rightNorm.x, eps)

        // 3. 5px 微调移动：归一化增量完全等于 5 / safeRect.width
        val moved5px = QuadEditorMath.pointerToNormalized(PointF(safeRect.left + 5f, safeRect.top + 200f), safeRect)
        val expectedDelta = 5f / safeRect.width
        assertEquals(expectedDelta, moved5px.x, eps)

        // 4. 真实角点位于 (0.0, 0.0) 和 (1.0, 1.0)，在 safe viewport 下仍能完整保留且无需人工裁剪偏移
        val cornerTL = PointF(0.0f, 0.0f)
        val cornerBR = PointF(1.0f, 1.0f)
        val quad = Quadrilateral(cornerTL, PointF(1.0f, 0.0f), cornerBR, PointF(0.0f, 1.0f))
        val updated = QuadEditorMath.applyCornerDrag(quad, 0, PointF(0.02f, 0.05f))
        assertNotNull(updated)
        assertEquals(0.02f, updated!!.topLeft.x, eps)
        assertEquals(0.05f, updated.topLeft.y, eps)
        assertEquals(1.0f, updated.bottomRight.x, eps)
        assertEquals(1.0f, updated.bottomRight.y, eps)
    }
}