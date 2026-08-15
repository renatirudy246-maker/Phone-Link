package com.phonelink.app

import com.phonelink.app.transfer.TransferManifest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferManifestTest {

    private fun sample() = TransferManifest(
        transferId = "t-abc123",
        senderDeviceId = "mobile-dev1",
        originalFileName = "t-abc123.jpg",
        mimeType = "image/jpeg",
        fileSize = 123456L,
        width = 3000,
        height = 4000,
        sha256 = "AB".repeat(32),
        capturedAt = "2026-08-15T10:00:00+08:00",
        sentAt = "2026-08-15T10:00:01+08:00",
        purpose = "Question",
    )

    @Test
    fun toJson_containsAllLowercaseFields() {
        val json = JSONObject(sample().toJson())
        assertEquals("t-abc123", json.getString("transferId"))
        assertEquals("mobile-dev1", json.getString("senderDeviceId"))
        assertEquals("t-abc123.jpg", json.getString("originalFileName"))
        assertEquals("image/jpeg", json.getString("mimeType"))
        assertEquals(123456L, json.getLong("fileSize"))
        assertEquals(3000, json.getInt("width"))
        assertEquals(4000, json.getInt("height"))
        assertEquals("AB".repeat(32), json.getString("sha256"))
        assertEquals("Question", json.getString("purpose"))
    }

    @Test
    fun transferId_generatedUnique() {
        val a = com.phonelink.app.transfer.TransferIds.newTransferId()
        val b = com.phonelink.app.transfer.TransferIds.newTransferId()
        assertTrue(a.startsWith("t-"))
        assertTrue(a != b)
        assertTrue(a.length <= 64)
    }

    @Test
    fun senderDeviceId_generatedUnique() {
        val a = com.phonelink.app.transfer.TransferIds.newSenderDeviceId()
        val b = com.phonelink.app.transfer.TransferIds.newSenderDeviceId()
        assertTrue(a.startsWith("mobile-"))
        assertTrue(a != b)
        assertTrue(a.length <= 128)
    }
}