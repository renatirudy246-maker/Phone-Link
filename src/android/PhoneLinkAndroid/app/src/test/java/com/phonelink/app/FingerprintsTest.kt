package com.phonelink.app

import com.phonelink.app.pairing.Fingerprints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FingerprintsTest {

    @Test
    fun `canonical colon format converted to okhttp pin`() {
        // SHA-256 十六进制（64 字符），冒号分隔大写
        val hex = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
        val pin = Fingerprints.toPin(hex)
        val expected = "sha256/" + Base64.getEncoder()
            .encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        assertEquals(expected, pin)
    }

    @Test
    fun `lowercase without colons accepted and normalized`() {
        val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(
            "sha256/" + Base64.getEncoder()
                .encodeToString(
                    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                ),
            Fingerprints.toPin(hex),
        )
    }

    @Test
    fun `non hex fingerprint rejected`() {
        try {
            Fingerprints.toPin("Z".repeat(64))
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `wrong length fingerprint rejected`() {
        try {
            Fingerprints.toPin("AB:CD")
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}