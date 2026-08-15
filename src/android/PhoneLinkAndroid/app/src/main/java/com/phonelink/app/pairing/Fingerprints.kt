package com.phonelink.app.pairing

import java.util.Base64

/**
 * 指纹规范化与 OkHttp pin 构建。
 * Windows 侧指纹为证书 DER 的 SHA-256，大写十六进制冒号分隔（AA:BB:...）。
 * OkHttp CertificatePinner 需要 sha256/<Base64(DER-SHA256)>。
 */
object Fingerprints {

    /** 校验规范格式并转换为 OkHttp pin 字符串。非法格式抛 IllegalArgumentException。 */
    fun toPin(fingerprint: String): String {
        val compact = fingerprint.replace(":", "").replace("-", "").uppercase()
        require(compact.length == 64) { "Fingerprint must be 64 hex chars, got ${compact.length}" }
        require(compact.all { it in '0'..'9' || it in 'A'..'F' }) { "Fingerprint must be hex" }

        val bytes = ByteArray(32)
        for (i in 0 until 32) {
            bytes[i] = compact.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return "sha256/" + Base64.getEncoder().encodeToString(bytes)
    }
}