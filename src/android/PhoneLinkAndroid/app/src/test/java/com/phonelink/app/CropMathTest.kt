package com.phonelink.app

import com.phonelink.app.crop.CropAnchor
import com.phonelink.app.crop.CropMath
import com.phonelink.app.crop.CropRect
import com.phonelink.app.crop.CropRectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CropRect / CropMath 纯数学映射测试（无 Android 依赖）。 */
class CropMathTest {

    private val eps = 0.0001f

    // ---- normalize ----

    @Test
    fun normalize_reversedSidesAreSwapped() {
        val r = CropMath.normalize(CropRectF(0.8f, 0.7f, 0.2f, 0.1f))
        assertEquals(0.2f, r.left, eps)
        assertEquals(0.1f, r.top, eps)
        assertEquals(0.8f, r.right, eps)
        assertEquals(0.7f, r.bottom, eps)
    }

    @Test
    fun normalize_alreadyOrderedIsUnchanged() {
        val r = CropMath.normalize(CropRectF(0.1f, 0.2f, 0.5f, 0.6f))
        assertEquals(0.1f, r.left, eps)
        assertEquals(0.6f, r.bottom, eps)
    }

    // ---- clamp ----

    @Test
    fun clamp_partiallyOutOfBoundsTranslatesBack() {
        val r = CropMath.clampToBounds(CropRectF(-0.2f, 0f, 0.4f, 0.5f))
        assertEquals(0f, r.left, eps)
        assertEquals(0.6f, r.right, eps)
        assertEquals(0.5f, r.height, eps)
    }

    @Test
    fun clamp_rightOutOfBoundsTranslatesLeft() {
        val r = CropMath.clampToBounds(CropRectF(0.6f, 0f, 1.3f, 0.5f))
        assertEquals(0.3f, r.left, eps)
        assertEquals(1f, r.right, eps)
        assertEquals(0.7f, r.width, eps)
    }

    @Test
    fun clamp_widerThanBoundsShrinks() {
        val r = CropMath.clampToBounds(CropRectF(-0.2f, 0f, 1.4f, 0.5f))
        assertEquals(0f, r.left, eps)
        assertEquals(1f, r.right, eps)
        assertEquals(0.5f, r.height, eps)
    }

    @Test
    fun clamp_bottomOutOfBoundsTranslatesUp() {
        val r = CropMath.clampToBounds(CropRectF(0f, 0.7f, 0.5f, 1.4f))
        assertEquals(0.3f, r.top, eps)
        assertEquals(1f, r.bottom, eps)
    }

    // ---- min size ----

    @Test
    fun enforceMinSize_expandsAroundBottomRightAnchor() {
        val r = CropMath.enforceMinSize(CropRectF(0.3f, 0.3f, 0.32f, 0.5f), 0.1f, 0.1f, CropAnchor.BOTTOM_RIGHT)
        assertEquals(0.22f, r.left, eps)
        assertEquals(0.3f, r.top, eps)
        assertEquals(0.32f, r.right, eps)
        assertEquals(0.5f, r.bottom, eps)
    }

    @Test
    fun enforceMinSize_expandsAroundTopAnchor() {
        val r = CropMath.enforceMinSize(CropRectF(0.3f, 0.3f, 0.5f, 0.32f), 0.1f, 0.1f, CropAnchor.TOP)
        assertEquals(0.3f, r.left, eps)
        assertEquals(0.3f, r.top, eps)
        assertEquals(0.5f, r.right, eps)
        assertEquals(0.4f, r.bottom, eps)
    }

    @Test
    fun enforceMinSize_moveKeepsCenter() {
        val r = CropMath.enforceMinSize(CropRectF(0.3f, 0.3f, 0.34f, 0.34f), 0.1f, 0.1f, CropAnchor.MOVE)
        assertEquals(0.27f, r.left, eps)
        assertEquals(0.37f, r.right, eps)
        assertEquals(0.27f, r.top, eps)
        assertEquals(0.37f, r.bottom, eps)
    }

    // ---- fit mapping (view <-> image) ----

    @Test
    fun fitScale_portraitImageOnLandscapeView() {
        val scale = CropMath.fitScale(900f, 600f, 750f, 1000f)
        assertEquals(0.6f, scale, eps)
    }

    @Test
    fun fitScale_landscapeImageOnPortraitView() {
        val scale = CropMath.fitScale(600f, 900f, 1000f, 750f)
        assertEquals(0.6f, scale, eps)
    }

    @Test
    fun fitRect_portraitLetterboxIsHorizontal() {
        // 竖图 750x1000 在横 view 900x600：显示 450x600，左右留白
        val area = CropMath.fitRect(900f, 600f, 750f, 1000f)
        assertEquals(225f, area.left, eps)
        assertEquals(0f, area.top, eps)
        assertEquals(675f, area.right, eps)
        assertEquals(600f, area.bottom, eps)
    }

    @Test
    fun fitRect_landscapeLetterboxIsVertical() {
        // 横图 1000x750 在竖 view 600x900：显示 600x450，上下留白
        val area = CropMath.fitRect(600f, 900f, 1000f, 750f)
        assertEquals(0f, area.left, eps)
        assertEquals(225f, area.top, eps)
        assertEquals(600f, area.right, eps)
        assertEquals(675f, area.bottom, eps)
    }

    @Test
    fun viewToImage_mapsThroughLetterbox() {
        // 竖图 750x1000 view 900x600：显示区 (225,0)-(675,600)，scale 0.6
        val (x, y) = CropMath.viewToImage(225f, 0f, 900f, 600f, 750f, 1000f)
        assertEquals(0f, x, eps)
        assertEquals(0f, y, eps)
    }

    @Test
    fun viewToImage_centerMapsToImageCenter() {
        val (x, y) = CropMath.viewToImage(450f, 300f, 900f, 600f, 750f, 1000f)
        assertEquals(375f, x, eps)
        assertEquals(500f, y, eps)
    }

    @Test
    fun imageToView_roundTripMatches() {
        val img = CropRectF(0.25f, 0.4f, 0.75f, 0.6f)
        for (corner in listOf(0.25f, 0.75f)) {
            for (edge in listOf(0.4f, 0.6f)) {
                val (vx, vy) = CropMath.imageToView(corner * 750f, edge * 1000f, 900f, 600f, 750f, 1000f)
                val (ix, iy) = CropMath.viewToImage(vx, vy, 900f, 600f, 750f, 1000f)
                assertEquals(corner * 750f, ix, eps)
                assertEquals(edge * 1000f, iy, eps)
            }
        }
    }

    @Test
    fun imageToView_landscapeRoundTripMatches() {
        val (vx, vy) = CropMath.imageToView(500f, 375f, 600f, 900f, 1000f, 750f)
        val (ix, iy) = CropMath.viewToImage(vx, vy, 600f, 900f, 1000f, 750f)
        assertEquals(500f, ix, eps)
        assertEquals(375f, iy, eps)
    }

    // ---- normalized <-> pixels ----

    @Test
    fun normalizedToPixels_fullImageIsWholeImage() {
        val px = CropMath.normalizedToPixels(CropRectF.FULL, 3000, 4000)
        assertEquals(0, px.left)
        assertEquals(0, px.top)
        assertEquals(3000, px.right)
        assertEquals(4000, px.bottom)
    }

    @Test
    fun normalizedToPixels_mapsProportionally() {
        val px = CropMath.normalizedToPixels(CropRectF(0.25f, 0.1f, 0.75f, 0.6f), 3000, 4000)
        assertEquals(750, px.left)
        assertEquals(400, px.top)
        assertEquals(2250, px.right)
        assertEquals(2400, px.bottom)
    }

    @Test
    fun normalizedToPixels_clampsOutOfRange() {
        val px = CropMath.normalizedToPixels(CropRectF(-0.5f, -0.5f, 1.5f, 1.5f), 3000, 4000)
        assertEquals(0, px.left)
        assertEquals(0, px.top)
        assertEquals(3000, px.right)
        assertEquals(4000, px.bottom)
        assertTrue(px.width >= 1)
        assertTrue(px.height >= 1)
    }

    @Test
    fun pixelsToNormalized_roundTrip() {
        val px = CropRect(750, 400, 2250, 2400)
        val norm = CropMath.pixelsToNormalized(px, 3000, 4000)
        assertEquals(0.25f, norm.left, eps)
        assertEquals(0.1f, norm.top, eps)
        assertEquals(0.75f, norm.right, eps)
        assertEquals(0.6f, norm.bottom, eps)
        val back = CropMath.normalizedToPixels(norm, 3000, 4000)
        assertEquals(px, back)
    }

    // ---- EXIF-normalized mapping ----

    @Test
    fun exifNormalizedMapping_isOrientationIndependent() {
        // 输入已是方向归一化像素（ImagePreparer 旋转后）。同一归一化区域在竖/横尺寸下映射一致。
        val portrait = CropMath.normalizedToPixels(CropRectF(0.1f, 0.2f, 0.6f, 0.8f), 3000, 4000)
        val landscape = CropMath.normalizedToPixels(CropRectF(0.1f, 0.2f, 0.6f, 0.8f), 4000, 3000)
        assertEquals(300, portrait.left)
        assertEquals(800, portrait.top)
        assertEquals(1800, portrait.right)
        assertEquals(3200, portrait.bottom)
        assertEquals(400, landscape.left)
        assertEquals(600, landscape.top)
        assertEquals(2400, landscape.right)
        assertEquals(2400, landscape.bottom)
        // 归一化比例完全一致
        assertEquals(portrait.width.toFloat() / 3000f, landscape.width.toFloat() / 4000f, eps)
        assertEquals(portrait.height.toFloat() / 4000f, landscape.height.toFloat() / 3000f, eps)
    }

    @Test
    fun reset_returnsFullImageRect() {
        assertEquals(0f, CropRectF.FULL.left, eps)
        assertEquals(0f, CropRectF.FULL.top, eps)
        assertEquals(1f, CropRectF.FULL.right, eps)
        assertEquals(1f, CropRectF.FULL.bottom, eps)
    }

    // ---- drag semantics ----

    private val full = CropRectF.FULL
    private val quarter = CropRectF(0.25f, 0.25f, 0.75f, 0.75f)

    @Test
    fun drag_rightEdgeMovesRightAndClampsAtOne() {
        val r = CropMath.applyDrag(quarter, CropAnchor.RIGHT, 0.4f, 0f)
        assertEquals(1f, r.right, eps)
        assertEquals(0.25f, r.left, eps)
        assertEquals(0.25f, r.top, eps)
    }

    @Test
    fun drag_leftEdgeRespectsMinWidth() {
        val start = CropRectF(0.6f, 0.25f, 0.7f, 0.75f)
        val r = CropMath.applyDrag(start, CropAnchor.LEFT, 0.5f, 0f)
        assertEquals(0.62f, r.left, eps)
        assertEquals(0.7f, r.right, eps)
        assertEquals(CropMath.DEFAULT_MIN_FRACTION, r.width, eps)
    }

    @Test
    fun drag_bottomLeftCornerMovesBothEdges() {
        val r = CropMath.applyDrag(quarter, CropAnchor.BOTTOM_LEFT, 0.1f, -0.2f)
        assertEquals(0.35f, r.left, eps)
        assertEquals(0.55f, r.bottom, eps)
        assertEquals(0.75f, r.right, eps)
        assertEquals(0.25f, r.top, eps)
    }

    @Test
    fun drag_moveTranslatesAndClampsInBounds() {
        val r = CropMath.applyDrag(quarter, CropAnchor.MOVE, 0.5f, 0.5f)
        assertEquals(0.5f, r.left, eps)
        assertEquals(1f, r.right, eps)
        assertEquals(0.5f, r.top, eps)
        assertEquals(1f, r.bottom, eps)
        assertEquals(0.5f, r.width, eps)
        assertEquals(0.5f, r.height, eps)
    }

    @Test
    fun drag_moveUpLeftClampsAtOrigin() {
        val r = CropMath.applyDrag(quarter, CropAnchor.MOVE, -0.5f, -0.5f)
        assertEquals(0f, r.left, eps)
        assertEquals(0f, r.top, eps)
        assertEquals(0.5f, r.right, eps)
        assertEquals(0.5f, r.bottom, eps)
    }

    @Test
    fun drag_topEdgeRespectsMinHeight() {
        val start = CropRectF(0.25f, 0.6f, 0.75f, 0.7f)
        val r = CropMath.applyDrag(start, CropAnchor.TOP, 0f, 0.5f)
        assertEquals(0.62f, r.top, eps)
        assertEquals(0.7f, r.bottom, eps)
        assertEquals(CropMath.DEFAULT_MIN_FRACTION, r.height, eps)
    }

    @Test
    fun drag_bottomEdgeClampsAtOne() {
        val r = CropMath.applyDrag(quarter, CropAnchor.BOTTOM, 0f, 0.4f)
        assertEquals(1f, r.bottom, eps)
        assertEquals(0.25f, r.top, eps)
    }
}