package com.phonelink.app.scanner.feedback

import java.io.File

/** Pending 队列中的一个样本包（目录 + 元数据）。 */
data class FeedbackPendingPackage(
    val dir: File,
    val reason: FeedbackReason,
    val createdAtMillis: Long,
    val totalBytes: Long,
) {
    val sampleId: String get() = dir.name
}

/**
 * Pending 队列淘汰策略（纯 Kotlin，JVM 单测）。
 * 价值排序（低→高）：CLEAN_SUCCESS < LOW_CONFIDENCE < USER_CORRECTED < MODEL_NOT_FOUND。
 * 清理时先删低价值，同级删最旧。
 */
object FeedbackQueuePolicy {

    private fun rank(reason: FeedbackReason): Int = when (reason) {
        FeedbackReason.CLEAN_SUCCESS -> 0
        FeedbackReason.LOW_CONFIDENCE -> 1
        FeedbackReason.USER_CORRECTED -> 2
        FeedbackReason.MODEL_NOT_FOUND -> 3
    }

    /** 优先淘汰顺序。 */
    fun evictionOrder(packages: List<FeedbackPendingPackage>): List<FeedbackPendingPackage> =
        packages.sortedWith(compareBy({ rank(it.reason) }, { it.createdAtMillis }))
}