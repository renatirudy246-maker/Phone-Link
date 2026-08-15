package com.phonelink.app

import com.phonelink.app.transfer.ImagePreparer
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreparerSha256Test {

    @Test
    fun sha256Hex_matchesReferenceDigest() {
        val content = ByteArray(200 * 1024) { (it % 251).toByte() }
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeBytes(content)
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { "%02X".format(it) }
            assertEquals(expected, ImagePreparer.sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sha256Hex_streamsLargeFileCorrectly() {
        // 超过 64KB 分块缓冲，验证 streaming 拼接正确
        val content = ByteArray(1_000_000) { (it % 7).toByte() }
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeBytes(content)
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { "%02X".format(it) }
            assertEquals(expected, ImagePreparer.sha256Hex(file))
        } finally {
            file.delete()
        }
    }
}