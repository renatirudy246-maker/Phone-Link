package com.phonelink.app.transfer

import org.json.JSONObject

/**
 * 上传 metadata（与 Phase 1 Windows 端 TransferManifest 契约一致，字段名小写固定）。
 */
data class TransferManifest(
    val transferId: String,
    val senderDeviceId: String,
    val originalFileName: String,
    val mimeType: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
    val capturedAt: String,
    val sentAt: String,
    val purpose: String,
) {
    fun toJson(): String = JSONObject()
        .put("transferId", transferId)
        .put("senderDeviceId", senderDeviceId)
        .put("originalFileName", originalFileName)
        .put("mimeType", mimeType)
        .put("fileSize", fileSize)
        .put("width", width)
        .put("height", height)
        .put("sha256", sha256)
        .put("capturedAt", capturedAt)
        .put("sentAt", sentAt)
        .put("purpose", purpose)
        .toString()
}

object TransferIds {
    fun newTransferId(): String = "t-" + java.util.UUID.randomUUID().toString().replace("-", "")

    fun newSenderDeviceId(): String = "mobile-" + java.util.UUID.randomUUID().toString().replace("-", "")
}