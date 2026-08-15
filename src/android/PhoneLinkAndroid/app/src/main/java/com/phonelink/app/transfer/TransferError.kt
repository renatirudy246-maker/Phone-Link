package com.phonelink.app.transfer

/**
 * 上传失败分类：所有文案面向用户（无 stack trace），
 * 保留机器可读类型供 UI 分支（如 REVOKED 触发重新配对）。
 */
enum class TransferFailureKind {
    DESKTOP_OFFLINE,
    AUTH_INVALID,
    DEVICE_REVOKED,
    SERVICE_PAUSED,
    NETWORK_TIMEOUT,
    FILE_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    TRANSFER_HASH_MISMATCH,
    TLS_FINGERPRINT,
    HTTP_500,
    PREPARE_FAILED,
    UNKNOWN,
}

data class TransferFailure(
    val kind: TransferFailureKind,
    val userMessage: String,
    val canRetry: Boolean = false,
)

object TransferErrorClassifier {

    fun fromHttp(statusCode: Int, code: String): TransferFailure = when (code) {
        "AUTH_INVALID" -> TransferFailure(
            TransferFailureKind.AUTH_INVALID,
            "登录已失效，请重新配对。",
        )
        "DEVICE_REVOKED" -> TransferFailure(
            TransferFailureKind.DEVICE_REVOKED,
            "该设备已被电脑撤销，请重新配对。",
        )
        "SERVICE_PAUSED" -> TransferFailure(
            TransferFailureKind.SERVICE_PAUSED,
            "电脑已暂停接收，请稍后在电脑上恢复接收后再试。",
            canRetry = true,
        )
        "FILE_TOO_LARGE" -> TransferFailure(
            TransferFailureKind.FILE_TOO_LARGE,
            "图片超过电脑限制（25MB）。",
        )
        "UNSUPPORTED_MEDIA_TYPE" -> TransferFailure(
            TransferFailureKind.UNSUPPORTED_MEDIA_TYPE,
            "电脑不支持该图片格式。",
        )
        "TRANSFER_HASH_MISMATCH" -> TransferFailure(
            TransferFailureKind.TRANSFER_HASH_MISMATCH,
            "传输校验失败（哈希不一致），请重试。",
            canRetry = true,
        )
        else -> when (statusCode) {
            500, 502, 503 -> TransferFailure(
                TransferFailureKind.HTTP_500,
                "电脑处理失败（${statusCode}），请重试。",
                canRetry = true,
            )
            401 -> TransferFailure(TransferFailureKind.AUTH_INVALID, "登录已失效，请重新配对。")
            403 -> TransferFailure(TransferFailureKind.DEVICE_REVOKED, "该设备已被电脑撤销，请重新配对。")
            404 -> TransferFailure(TransferFailureKind.UNKNOWN, "电脑未找到该传输记录，请重试。", canRetry = true)
            else -> TransferFailure(TransferFailureKind.UNKNOWN, "发送失败（${statusCode}）。", canRetry = true)
        }
    }

    fun fromIo(exception: Exception): TransferFailure {
        val message = exception.message.orEmpty()
        return when {
            exception is javax.net.ssl.SSLHandshakeException ||
                exception is javax.net.ssl.SSLPeerUnverifiedException -> TransferFailure(
                TransferFailureKind.TLS_FINGERPRINT,
                "电脑身份发生变化，需要重新确认配对。",
            )
            exception is java.net.ConnectException ||
                exception is java.net.SocketTimeoutException ||
                exception is java.net.UnknownHostException -> TransferFailure(
                TransferFailureKind.NETWORK_TIMEOUT,
                "无法连接电脑，请确认手机和电脑连接到同一局域网。",
                canRetry = true,
            )
            else -> TransferFailure(
                TransferFailureKind.NETWORK_TIMEOUT,
                "发送失败，网络中断。",
                canRetry = true,
            )
        }
    }
}