package com.phonelink.app.scanner.feedback

import com.phonelink.app.scanner.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A/B/C/D：样本采集决策规则。 */
class ScannerFeedbackDecisionTest {

    private val predicted = FeedbackQuad(
        tl = PointF(0.1f, 0.1f),
        tr = PointF(0.9f, 0.1f),
        br = PointF(0.9f, 0.9f),
        bl = PointF(0.1f, 0.9f),
    )

    /** tail 0x63 → 99，命中不了 5% CLEAN_SUCCESS 采样。 */
    private val noCleanSampleId = "sf-00000000000000000000000000000063"

    /** tail 0x00 → 0，必中 5% 采样。 */
    private val cleanSampleId = "sf-00000000000000000000000000000000"

    @Test
    fun `A detected without adjustment usually not collected`() {
        val outcome = ScannerFeedbackDecision.decide(noCleanSampleId, FeedbackDetectionStatus.DETECTED, predicted, predicted)
        assertNull(outcome)
    }

    @Test
    fun `A detected without adjustment hits deterministic clean success`() {
        val outcome = ScannerFeedbackDecision.decide(cleanSampleId, FeedbackDetectionStatus.DETECTED, predicted, predicted)
        assertNotNull(outcome)
        assertEquals(FeedbackReason.CLEAN_SUCCESS, outcome!!.reason)
        assertEquals(0f, outcome.correction.maxDelta, 1e-6f)
        assertTrue(outcome.correction.adjustedCorners.isEmpty())
    }

    @Test
    fun `B detected with significant adjustment is user corrected`() {
        val corrected = predicted.copy(tr = PointF(0.95f, 0.05f)) // maxDelta ≈ 0.049 > 0.003
        val outcome = ScannerFeedbackDecision.decide(noCleanSampleId, FeedbackDetectionStatus.DETECTED, predicted, corrected)
        assertNotNull(outcome)
        assertEquals(FeedbackReason.USER_CORRECTED, outcome!!.reason)
        assertTrue(outcome.correction.maxDelta >= ScannerFeedbackConfig.USER_CORRECTED_MIN_MAX_DELTA)
        assertEquals(listOf("TR"), outcome.correction.adjustedCorners)
        assertTrue(outcome.correction.meanDelta > 0f)
    }

    @Test
    fun `C low confidence collected regardless of adjustment size`() {
        val tinyAdjustment = predicted.copy(bl = PointF(0.1005f, 0.9f)) // 远小于阈值
        val outcome = ScannerFeedbackDecision.decide(
            noCleanSampleId,
            FeedbackDetectionStatus.LOW_CONFIDENCE,
            predicted,
            tinyAdjustment,
        )
        assertNotNull(outcome)
        assertEquals(FeedbackReason.LOW_CONFIDENCE, outcome!!.reason)
        assertTrue(outcome.correction.maxDelta < ScannerFeedbackConfig.USER_CORRECTED_MIN_MAX_DELTA)
    }

    @Test
    fun `C low confidence with zero adjustment still collected`() {
        val outcome = ScannerFeedbackDecision.decide(noCleanSampleId, FeedbackDetectionStatus.LOW_CONFIDENCE, predicted, predicted)
        assertNotNull(outcome)
        assertEquals(FeedbackReason.LOW_CONFIDENCE, outcome!!.reason)
    }

    @Test
    fun `D not found with confirmed quad is model not found`() {
        val outcome = ScannerFeedbackDecision.decide(noCleanSampleId, FeedbackDetectionStatus.NOT_FOUND, null, predicted)
        assertNotNull(outcome)
        assertEquals(FeedbackReason.MODEL_NOT_FOUND, outcome!!.reason)
        assertNull(outcome.predictedQuad)
        assertTrue(outcome.correction.predictionMissing)
        assertTrue(outcome.correction.adjustedCorners.isEmpty())
    }
}