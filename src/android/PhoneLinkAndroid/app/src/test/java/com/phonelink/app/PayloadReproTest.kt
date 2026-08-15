package com.phonelink.app

import org.junit.Test

class PayloadReproTest {
    @Test
    fun `reproduce actual payload decode`() {
        val raw = java.io.File("C:\\Users\\Yy\\AppData\\Local\\Temp\\phonelink-device-pair.txt").readText().trim()
        println("payload length: ${raw.length}")

        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(raw)
        } catch (e: Exception) {
            println("Base64 decode FAILED: ${e::class.java.simpleName}: ${e.message}")
            return
        }
        println("decoded bytes: ${bytes.size}")

        val text = String(bytes, Charsets.UTF_8)
        println("decoded text: $text")

        try {
            val json = org.json.JSONObject(text)
            println("JSON parse OK, keys: ${json.keys().asSequence().toList()}")
        } catch (e: Exception) {
            println("JSON parse FAILED: ${e::class.java.simpleName}: ${e.message}")
        }
    }
}