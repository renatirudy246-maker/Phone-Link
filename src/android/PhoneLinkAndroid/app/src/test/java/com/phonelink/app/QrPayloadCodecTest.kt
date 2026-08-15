package com.phonelink.app

import com.phonelink.app.pairing.QrPayloadCodec
import com.phonelink.app.pairing.QrPayloadFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class QrPayloadCodecTest {

    private fun encode(payloadJson: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))

    @Test
    fun `decodes valid payload`() {
        val json = """
            {"ProtocolVersion":1,"DesktopDeviceId":"desktop-abc","DesktopDeviceName":"DESKTOP-X",
             "Host":"192.168.1.5","Port":8484,"OneTimeToken":"tok123","CertificateFingerprint":"AA:BB:CC",
             "ExpiresAt":"2026-08-16T10:00:00Z"}
        """.trimIndent()
        val payload = QrPayloadCodec.decode(encode(json))
        assertEquals(1, payload.protocolVersion)
        assertEquals("desktop-abc", payload.desktopDeviceId)
        assertEquals("192.168.1.5", payload.host)
        assertEquals(8484, payload.port)
        assertEquals("tok123", payload.oneTimeToken)
        assertTrue(payload.expiresAtEpochMillis > 0)
    }

    @Test
    fun `missing required field rejected`() {
        val json = """
            {"ProtocolVersion":1,"DesktopDeviceId":"desktop-abc","DesktopDeviceName":"DESKTOP-X",
             "Host":"192.168.1.5","Port":8484,"CertificateFingerprint":"AA:BB:CC",
             "ExpiresAt":"2026-08-16T10:00:00Z"}
        """.trimIndent()
        try {
            QrPayloadCodec.decode(encode(json))
            fail("expected QrPayloadFormatException")
        } catch (e: QrPayloadFormatException) {
            assertTrue(e.message!!.contains("missing required fields"))
        }
    }

    @Test
    fun `invalid base64url rejected`() {
        try {
            QrPayloadCodec.decode("!!not-base64!!")
            fail("expected QrPayloadFormatException")
        } catch (e: QrPayloadFormatException) {
            assertTrue(e.message!!.contains("Base64URL"))
        }
    }

    @Test
    fun `non json rejected`() {
        try {
            QrPayloadCodec.decode(encode("hello"))
            fail("expected QrPayloadFormatException")
        } catch (e: QrPayloadFormatException) {
            assertTrue(e.message!!.contains("compact JSON"))
        }
    }

    @Test
    fun `oversized payload rejected`() {
        val big = "A".repeat(QrPayloadCodec.MAX_LENGTH + 1)
        try {
            QrPayloadCodec.decode(big)
            fail("expected QrPayloadFormatException")
        } catch (e: QrPayloadFormatException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun `invalid port rejected`() {
        val json = """
            {"ProtocolVersion":1,"DesktopDeviceId":"desktop-abc","DesktopDeviceName":"DESKTOP-X",
             "Host":"192.168.1.5","Port":70000,"OneTimeToken":"tok123","CertificateFingerprint":"AA:BB:CC",
             "ExpiresAt":"2026-08-16T10:00:00Z"}
        """.trimIndent()
        try {
            QrPayloadCodec.decode(encode(json))
            fail("expected QrPayloadFormatException")
        } catch (e: QrPayloadFormatException) {
            assertTrue(e.message!!.contains("invalid fields"))
        }
    }
}