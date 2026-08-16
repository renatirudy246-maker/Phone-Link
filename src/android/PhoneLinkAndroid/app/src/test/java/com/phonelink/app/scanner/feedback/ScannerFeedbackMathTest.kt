package com.phonelink.app.scanner.feedback

import com.phonelink.app.scanner.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H：归一化 / delta 数学。 */
class ScannerFeedbackMathTest {

    private val unit = FeedbackQuad(
        tl = PointF(0.1f, 0.1f),
        tr = PointF(0.9f, 0.1f),
        br = PointF(0.9f, 0.9f),
        bl = PointF(0.1f, 0.9f),
    )

    @Test
    fun `identical quads produce zero deltas`() {
        val deltas = ScannerFeedbackMath.cornerDeltas(unit, unit)
        assertEquals(4, deltas.size)
        deltas.forEach { assertEquals(0f, it, 1e-6f) }
        assertEquals(0f, ScannerFeedbackMath.mean(deltas), 1e-6f)
        assertEquals(0f, ScannerFeedbackMath.max(deltas), 1e-6f)
    }

    @Test
    fun `single corner move is normalized by sqrt2 diagonal`() {
        val corrected = unit.copy(tr = PointF(0.91f, 0.1f)) // x +0.01
        val deltas = ScannerFeedbackMath.cornerDeltas(unit, corrected)
        assertEquals(0.01f / kotlin.math.sqrt(2.0f), deltas[1], 1e-5f)
        assertEquals(0f, deltas[0], 1e-6f)
        assertEquals(0f, deltas[2], 1e-6f)
        assertEquals(0f, deltas[3], 1e-6f)
    }

    @Test
    fun `adjustedCorners lists only moved corners`() {
        val corrected = unit.copy(
            tr = PointF(0.92f, 0.1f),
            br = PointF(0.9f, 0.88f),
        )
        val deltas = ScannerFeedbackMath.cornerDeltas(unit, corrected)
        val adjusted = ScannerFeedbackMath.adjustedCorners(deltas, ScannerFeedbackConfig.CORNER_ADJUSTED_EPSILON)
        assertEquals(listOf("TR", "BR"), adjusted)
    }

    @Test
    fun `mean and max deltas`() {
        val deltas = floatArrayOf(0.0f, 0.01f, 0.02f, 0.03f)
        assertEquals(0.015f, ScannerFeedbackMath.mean(deltas), 1e-6f)
        assertEquals(0.03f, ScannerFeedbackMath.max(deltas), 1e-6f)
    }

    @Test
    fun `clean success sampling is deterministic`() {
        val hit = "sf-00000000000000000000000000000000" // tail 0x00 → 0
        val miss = "sf-00000000000000000000000000000063" // tail 0x63 → 99
        assertTrue(ScannerFeedbackMath.shouldSampleCleanSuccess(hit, 5))
        assertFalse(ScannerFeedbackMath.shouldSampleCleanSuccess(miss, 5))
        assertEquals(
            ScannerFeedbackMath.shouldSampleCleanSuccess(hit, 5),
            ScannerFeedbackMath.shouldSampleCleanSuccess(hit, 5),
        )
        assertEquals(
            ScannerFeedbackMath.shouldSampleCleanSuccess(miss, 5),
            ScannerFeedbackMath.shouldSampleCleanSuccess(miss, 5),
        )
    }

    @Test
    fun `sampling rate bounds`() {
        assertFalse(ScannerFeedbackMath.shouldSampleCleanSuccess("sf-anything", 0))
        assertTrue(ScannerFeedbackMath.shouldSampleCleanSuccess("sf-anything", 100))
    }
}