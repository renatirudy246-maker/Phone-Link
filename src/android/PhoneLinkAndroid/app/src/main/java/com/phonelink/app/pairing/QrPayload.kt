package com.phonelink.app.pairing

import java.util.Base64
import org.json.JSONObject

/**
 * QR 配对 payload：compact JSON + Base64URL（无 padding）。
 * 与 Windows 侧 PairingQrCodec 严格一致（协议契约，字段名固定）。
 */
data class QrPayload(
    val protocolVersion: Int,
    val desktopDeviceId: String,
    val desktopDeviceName: String,
    val host: String,
    val port: Int,
    val oneTimeToken: String,
    val certificateFingerprint: String,
    val expiresAtEpochMillis: Long,
)

class QrPayloadFormatException(message: String) : Exception(message)

object QrPayloadCodec {

    const val MAX_LENGTH = 2048
    const val PROTOCOL_VERSION = 1

    @Throws(QrPayloadFormatException::class)
    fun decode(raw: String): QrPayload {
        if (raw.isBlank() || raw.length > MAX_LENGTH) {
            throw QrPayloadFormatException("QR payload is missing or too large.")
        }

        val jsonBytes = try {
            Base64.getUrlDecoder().decode(raw)
        } catch (e: IllegalArgumentException) {
            throw QrPayloadFormatException("QR payload is not valid Base64URL.")
        }

        val json = try {
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw QrPayloadFormatException("QR payload is not valid compact JSON.")
        }

        val payload = try {
            QrPayload(
                protocolVersion = json.getInt("ProtocolVersion"),
                desktopDeviceId = json.getString("DesktopDeviceId"),
                desktopDeviceName = json.getString("DesktopDeviceName"),
                host = json.getString("Host"),
                port = json.getInt("Port"),
                oneTimeToken = json.getString("OneTimeToken"),
                certificateFingerprint = json.getString("CertificateFingerprint"),
                expiresAtEpochMillis = parseExpiry(json.getString("ExpiresAt")),
            )
        } catch (e: Exception) {
            throw QrPayloadFormatException("QR payload has missing required fields.")
        }

        validate(payload)
        return payload
    }

    private fun parseExpiry(value: String): Long {
        val parsed = java.time.OffsetDateTime.parse(value)
        return parsed.toInstant().toEpochMilli()
    }

    private fun validate(payload: QrPayload) {
        if (payload.desktopDeviceId.isBlank() || payload.desktopDeviceName.isBlank() ||
            payload.host.isBlank() || payload.oneTimeToken.isBlank() ||
            payload.certificateFingerprint.isBlank() || payload.port !in 1..65535
        ) {
            throw QrPayloadFormatException("QR payload has invalid fields.")
        }
    }
}