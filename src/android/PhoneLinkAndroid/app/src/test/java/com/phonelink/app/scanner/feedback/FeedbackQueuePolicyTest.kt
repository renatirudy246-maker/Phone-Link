package com.phonelink.app.scanner.feedback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** I：队列淘汰策略（价值优先，同级旧样本优先）。 */
class FeedbackQueuePolicyTest {

    private fun pkg(name: String, reason: FeedbackReason, ageMillis: Long): FeedbackPendingPackage {
        val root = Files.createTempDirectory("fb-policy").toFile()
        val dir = File(root, name)
        dir.mkdirs()
        dir.setLastModified(ageMillis)
        return FeedbackPendingPackage(
            dir = dir,
            reason = reason,
            createdAtMillis = ageMillis,
            totalBytes = 10L,
        )
    }

    @Test
    fun `lower value reasons are evicted first`() {
        val packages = listOf(
            pkg("b", FeedbackReason.MODEL_NOT_FOUND, 200L),
            pkg("a", FeedbackReason.CLEAN_SUCCESS, 100L),
            pkg("c", FeedbackReason.USER_CORRECTED, 300L),
            pkg("d", FeedbackReason.LOW_CONFIDENCE, 400L),
        )
        val order = FeedbackQueuePolicy.evictionOrder(packages)
        assertEquals(
            listOf("a", "d", "c", "b"),
            order.map { it.sampleId },
        )
    }

    @Test
    fun `oldest wins among same value`() {
        val packages = listOf(
            pkg("new", FeedbackReason.LOW_CONFIDENCE, 900L),
            pkg("old", FeedbackReason.LOW_CONFIDENCE, 100L),
        )
        val order = FeedbackQueuePolicy.evictionOrder(packages)
        assertEquals(listOf("old", "new"), order.map { it.sampleId })
    }
}