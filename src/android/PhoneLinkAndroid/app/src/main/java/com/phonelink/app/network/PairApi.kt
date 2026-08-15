package com.phonelink.app.network

import com.phonelink.app.pairing.QrPayload
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PairApiException(
    val statusCode: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : Exception("HTTP $statusCode $code: $message")

class PairApi(
    private val clientFactory: (String) -> okhttp3.OkHttpClient,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val mobileDeviceName: String,
    val mobileDeviceId: String,
) {

    fun newCallBuilder(): okhttp3.Request.Builder = Request.Builder()

    fun baseUrl(): String = "https://$host:$port"

    private fun client() = clientFactory(fingerprint)

    /** POST /v1/pair：一次性 token 换长期设备 token。 */
    fun pair(oneTimeToken: String): PairResult {
        val body = JSONObject()
            .put("oneTimeToken", oneTimeToken)
            .put("mobileDeviceId", mobileDeviceId)
            .put("mobileDeviceName", mobileDeviceName)
            .put("platform", "android")
            .put("protocolVersion", QrPayloadCodecVersion)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(baseUrl().toHttpUrl().newBuilder().addPathSegment("v1").addPathSegment("pair").build())
            .post(body)
            .build()

        client().newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw parseError(response.code, text)
            }
            val json = JSONObject(text)
            return PairResult(
                deviceToken = json.getString("deviceToken"),
                desktopDeviceId = json.getString("desktopDeviceId"),
                protocolVersion = json.optInt("protocolVersion", -1),
            )
        }
    }

    /** GET /v1/health 带 Bearer：验证 token 与指纹同时有效。 */
    fun health(token: String): HealthResult {
        val request = Request.Builder()
            .url(baseUrl().toHttpUrl().newBuilder().addPathSegment("v1").addPathSegment("health").build())
            .header("Authorization", "Bearer $token")
            .build()

        client().newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw parseError(response.code, text)
            }
            val json = JSONObject(text)
            return HealthResult(
                deviceId = json.optString("deviceId", ""),
                deviceName = json.optString("deviceName", ""),
                status = json.getString("status"),
            )
        }
    }

    private fun parseError(statusCode: Int, text: String): PairApiException {
        val code = try {
            JSONObject(text).getString("code")
        } catch (_: Exception) {
            "UNKNOWN"
        }
        val retryable = try {
            JSONObject(text).optBoolean("retryable", false)
        } catch (_: Exception) {
            false
        }
        return PairApiException(statusCode, code, text, retryable)
    }

    data class PairResult(
        val deviceToken: String,
        val desktopDeviceId: String,
        val protocolVersion: Int,
    )

    data class HealthResult(
        val deviceId: String,
        val deviceName: String,
        val status: String,
    )

    private companion object {
        const val QrPayloadCodecVersion = 1
    }
}