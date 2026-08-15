package com.phonelink.app.transfer

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject

/**
 * 上传层：与 Phase 2 相同的钉扎连接（PinnedClientFactory 提供 clientFactory），
 * multipart 顺序 = metadata 先于 file（Phase 1 协议约束）。
 * 进度基于真实 bytes written（64KB 分块累计）。
 * 幂等：失败不确定时调用方通过 getStatus 查询，避免重复落盘。
 */
class TransferRepository(
    clientFactory: (String) -> OkHttpClient,
    private val host: String,
    private val port: Int,
    private val fingerprint: String,
    private val token: String,
) {
    private val client: OkHttpClient = clientFactory(fingerprint).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    sealed interface UploadResult {
        data class Success(val transferId: String) : UploadResult
        data class HttpError(val statusCode: Int, val code: String, val message: String) : UploadResult
        data class IoError(val exception: Exception) : UploadResult
    }

    sealed interface StatusResult {
        data class Completed(val transferId: String) : StatusResult
        data object NotFound : StatusResult
        data class Failed(val errorCode: String?) : StatusResult
        data class Error(val exception: Exception) : StatusResult
    }

    fun upload(manifest: TransferManifest, file: File, onProgress: (Long, Long) -> Unit): UploadResult {
        val boundary = "phonelink-" + java.util.UUID.randomUUID().toString()
        val body = object : RequestBody() {
            override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("--$boundary\r\n")
                sink.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
                sink.writeUtf8(manifest.toJson())
                sink.writeUtf8("\r\n")
                sink.writeUtf8("--$boundary\r\n")
                sink.writeUtf8("Content-Disposition: form-data; name=\"file\"; filename=\"${manifest.originalFileName}\"\r\n")
                sink.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        total += read
                        onProgress(total, manifest.fileSize)
                    }
                }
                sink.writeUtf8("\r\n--$boundary--\r\n")
            }
        }

        val request = Request.Builder()
            .url("https://$host:$port/v1/transfers")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    UploadResult.Success(manifest.transferId)
                } else {
                    val text = response.body?.string().orEmpty()
                    val code = try {
                        JSONObject(text).getString("code")
                    } catch (_: Exception) {
                        "UNKNOWN"
                    }
                    UploadResult.HttpError(response.code, code, text)
                }
            }
        } catch (e: Exception) {
            UploadResult.IoError(e)
        }
    }

    /** 上传结果不确定时的状态确认（幂等）：Completed → 本地标成功；NotFound/Failed → 可复用同 ID 重试。 */
    fun getStatus(transferId: String): StatusResult {
        val request = Request.Builder()
            .url("https://$host:$port/v1/transfers/$transferId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        val status = try {
                            JSONObject(text).getString("status")
                        } catch (_: Exception) {
                            ""
                        }
                        if (status == "completed") {
                            StatusResult.Completed(transferId)
                        } else {
                            StatusResult.Failed(null)
                        }
                    }
                    response.code == 404 -> StatusResult.NotFound
                    else -> {
                        val text = response.body?.string().orEmpty()
                        val code = try {
                            JSONObject(text).getString("code")
                        } catch (_: Exception) {
                            null
                        }
                        StatusResult.Failed(code)
                    }
                }
            }
        } catch (e: Exception) {
            StatusResult.Error(e)
        }
    }
}