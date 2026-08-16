package com.phonelink.app.scanner.feedback

import com.phonelink.app.scanner.DetectionStatus
import com.phonelink.app.scanner.PointF
import com.phonelink.app.scanner.Quadrilateral
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E/F/G/I + 打包：collector 生命周期与队列。 */
class ScannerFeedbackCollectorTest {

    private lateinit var tempDir: File

    private fun newCollector(enabled: Boolean = true): ScannerFeedbackCollector {
        tempDir = Files.createTempDirectory("fb-test").toFile()
        return ScannerFeedbackCollector(pendingDir = File(tempDir, "pending"), enabled = { enabled })
    }

    private fun sourceFile(name: String = "source.jpg"): File =
        File(tempDir, name).apply { writeText("fake-jpeg-bytes") }

    private val sampleQuad = Quadrilateral(
        topLeft = PointF(0.1f, 0.1f),
        topRight = PointF(0.9f, 0.1f),
        bottomRight = PointF(0.9f, 0.9f),
        bottomLeft = PointF(0.1f, 0.9f),
    )

    private fun beginDetectedSession(collector: ScannerFeedbackCollector) =
        collector.beginSession(
            sourceFile = sourceFile(),
            sourceWidth = 1000,
            sourceHeight = 2000,
            sourceSha256 = "AA".repeat(32),
            status = DetectionStatus.DETECTED,
            confidence = 0.9f,
            qualityReason = null,
            maskAreaRatio = 0.7f,
            heatmap = null,
            predictedQuad = sampleQuad,
        )

    @Test
    fun `E cancel editor does not collect`() {
        val collector = newCollector()
        val session = beginDetectedSession(collector)
        assertNotNull(session)
        collector.cancelSession()
        assertNull(collector.activeSession)
        val queued = collector.confirmCorrection(session, sampleQuad)
        assertFalse(queued)
        assertTrue(collector.pendingPackages().isEmpty())
    }

    @Test
    fun `F feedback setting off does not collect`() {
        val collector = newCollector(enabled = false)
        val session = beginDetectedSession(collector)
        assertNull(session)
        val queued = collector.confirmCorrection(null, sampleQuad)
        assertFalse(queued)
        assertTrue(collector.pendingPackages().isEmpty())
    }

    @Test
    fun `G same session retried keeps same sampleId without duplicate package`() {
        val collector = newCollector()
        val session = beginDetectedSession(collector)
        assertNotNull(session)
        val corrected = session!!.predictedQuad!!.let {
            it.copy(tr = PointF(it.tr.x + 0.02f, it.tr.y))
        }
        // 需要重新构造 Quadrilateral（FeedbackQuad 转换）
        val correctedQuad = Quadrilateral(
            topLeft = PointF(corrected.tl.x, corrected.tl.y),
            topRight = PointF(corrected.tr.x, corrected.tr.y),
            bottomRight = PointF(corrected.br.x, corrected.br.y),
            bottomLeft = PointF(corrected.bl.x, corrected.bl.y),
        )
        assertTrue(collector.confirmCorrection(session, correctedQuad))
        val packages = collector.pendingPackages()
        assertEquals(1, packages.size)
        assertEquals(session.sampleId, packages[0].sampleId)

        // 同一 session 再次确认：不产生重复包
        assertFalse(collector.confirmCorrection(session, correctedQuad))
        assertEquals(1, collector.pendingPackages().size)
        assertEquals(session.sampleId, collector.pendingPackages()[0].sampleId)
    }

    @Test
    fun `G package metadata matches session identity`() {
        val collector = newCollector()
        val session = beginDetectedSession(collector)!!
        val corrected = session.predictedQuad!!.let {
            Quadrilateral(
                topLeft = PointF(it.tl.x, it.tl.y),
                topRight = PointF(it.tr.x + 0.02f, it.tr.y),
                bottomRight = PointF(it.br.x, it.br.y),
                bottomLeft = PointF(it.bl.x, it.bl.y),
            )
        }
        assertTrue(collector.confirmCorrection(session, corrected))
        val pkg = collector.pendingPackages()[0]
        val meta = JSONObject(File(pkg.dir, "metadata.json").readText())
        assertEquals(session.sampleId, meta.getString("sampleId"))
        assertEquals(1, meta.getInt("schemaVersion"))
        assertEquals(1000, meta.getJSONObject("source").getInt("width"))
        assertEquals(2000, meta.getJSONObject("source").getInt("height"))
        assertEquals("AA".repeat(32), meta.getJSONObject("source").getString("sha256"))
        assertEquals("DocQuadNet-256", meta.getJSONObject("model").getString("name"))
        assertEquals(ScannerFeedbackConfig.MODEL_SHA256, meta.getJSONObject("model").getString("sha256"))
        assertEquals("user_confirmed_quad", meta.getString("labelSource"))
        assertEquals("Detected", meta.getJSONObject("detection").getString("status"))
        assertEquals("USER_CORRECTED", meta.getString("reason"))
        assertTrue(File(pkg.dir, "source.jpg").exists())
    }

    @Test
    fun `I not found package has null predictedQuad`() {
        val collector = newCollector()
        val session = collector.beginSession(
            sourceFile = sourceFile(),
            sourceWidth = 640,
            sourceHeight = 480,
            sourceSha256 = "BB".repeat(32),
            status = DetectionStatus.NOT_FOUND,
            confidence = null,
            qualityReason = "no document",
            maskAreaRatio = null,
            heatmap = null,
            predictedQuad = null,
        )!!
        assertTrue(collector.confirmCorrection(session, sampleQuad))
        val meta = JSONObject(File(collector.pendingPackages()[0].dir, "metadata.json").readText())
        assertTrue(meta.isNull("predictedQuad"))
        assertEquals("MODEL_NOT_FOUND", meta.getString("reason"))
        assertTrue(meta.getJSONObject("correction").getBoolean("predictionMissing"))
    }

    @Test
    fun `I queue eviction keeps high value samples`() {
        val collector = newCollector()
        // 直接构造 101 个包：100 个 CLEAN_SUCCESS + 1 个 MODEL_NOT_FOUND（都是旧样本）
        for (i in 0 until 100) {
            writePackageDir(collector, "sf-clean$i", FeedbackReason.CLEAN_SUCCESS)
        }
        writePackageDir(collector, "sf-notfound", FeedbackReason.MODEL_NOT_FOUND)
        assertEquals(101, collector.pendingPackages().size)

        val removed = collector.enforceLimits()
        assertEquals(1, removed)
        val remaining = collector.pendingPackages()
        assertEquals(ScannerFeedbackConfig.MAX_PENDING_SAMPLES, remaining.size)
        assertTrue(remaining.any { it.sampleId == "sf-notfound" })
    }

    private fun writePackageDir(collector: ScannerFeedbackCollector, sampleId: String, reason: FeedbackReason) {
        val dir = File(collector.pendingDir, sampleId)
        dir.mkdirs()
        File(dir, "metadata.json").writeText(
            JSONObject().put("sampleId", sampleId).put("reason", reason.name).toString(),
        )
        File(dir, "source.jpg").writeText("x")
        dir.setLastModified(1_000_000L)
    }
}