package com.phonelink.app.scanner.feedback

/**
 * 样本采集决策（纯 Kotlin，JVM 单测）。
 * 只回答"这个已确认四边形是否值得采集、以什么原因采集"，
 * 不做任何文件/网络操作。
 */
object ScannerFeedbackDecision {

    data class Outcome(
        val reason: FeedbackReason,
        val predictedQuad: FeedbackQuad?,
        val correction: FeedbackCorrection,
    )

    /**
     * A/B/C/D 规则：
     * - NOT_FOUND（prediction 不可信，为 null）      → MODEL_NOT_FOUND（最高价值）
     * - LOW_CONFIDENCE                              → LOW_CONFIDENCE（无论调整大小）
     * - DETECTED + maxDelta ≥ 阈值                  → USER_CORRECTED
     * - DETECTED + 无明显调整                        → 确定性 5% CLEAN_SUCCESS，否则不采集（返回 null）
     */
    fun decide(
        sampleId: String,
        status: FeedbackDetectionStatus,
        predictedQuad: FeedbackQuad?,
        correctedQuad: FeedbackQuad,
    ): Outcome? = when (status) {
        FeedbackDetectionStatus.NOT_FOUND -> Outcome(
            reason = FeedbackReason.MODEL_NOT_FOUND,
            predictedQuad = null,
            correction = FeedbackCorrection(
                meanDelta = 0f,
                maxDelta = 0f,
                adjustedCorners = emptyList(),
                predictionMissing = true,
            ),
        )

        FeedbackDetectionStatus.LOW_CONFIDENCE -> Outcome(
            reason = FeedbackReason.LOW_CONFIDENCE,
            predictedQuad = predictedQuad,
            correction = buildCorrection(predictedQuad?.let { ScannerFeedbackMath.cornerDeltas(it, correctedQuad) }),
        )

        FeedbackDetectionStatus.DETECTED -> {
            val predicted = predictedQuad ?: return null
            val correction = buildCorrection(ScannerFeedbackMath.cornerDeltas(predicted, correctedQuad))
            when {
                correction.maxDelta >= ScannerFeedbackConfig.USER_CORRECTED_MIN_MAX_DELTA ->
                    Outcome(FeedbackReason.USER_CORRECTED, predicted, correction)

                ScannerFeedbackMath.shouldSampleCleanSuccess(
                    sampleId,
                    ScannerFeedbackConfig.CLEAN_SUCCESS_SAMPLE_RATE_PERCENT,
                ) -> Outcome(FeedbackReason.CLEAN_SUCCESS, predicted, correction)

                else -> null
            }
        }
    }

    private fun buildCorrection(deltas: FloatArray?): FeedbackCorrection {
        if (deltas == null) {
            return FeedbackCorrection(0f, 0f, emptyList(), predictionMissing = true)
        }
        return FeedbackCorrection(
            meanDelta = ScannerFeedbackMath.mean(deltas),
            maxDelta = ScannerFeedbackMath.max(deltas),
            adjustedCorners = ScannerFeedbackMath.adjustedCorners(
                deltas,
                ScannerFeedbackConfig.CORNER_ADJUSTED_EPSILON,
            ),
            predictionMissing = false,
        )
    }
}