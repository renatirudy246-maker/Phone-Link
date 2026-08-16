package com.phonelink.app.discovery

import android.content.Context
import android.util.Log
import com.phonelink.app.network.PinnedClientFactory
import com.phonelink.app.pairing.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 统一端点解析与自愈管理器 (EndpointResolver)。
 *
 * 核心逻辑：
 * 1. 快速直连 (Fast Path)：优先探测上次缓存的 host:port（1.2s 超时）；
 * 2. 动态自愈 (Recovery Path)：
 *    - 优先尝试 NSD / mDNS；
 *    - 失败则自动触发 UDP 局域网广播发现 (端口 8485)；
 * 3. 零信任校验 (Zero-Trust Validation)：
 *    - 任何候选端点必须通过 TLS 证书 SHA-256 指纹校验与 /v1/health 探测；
 *    - 校验完全一致后，原子更新本地端点缓存。
 */
class EndpointResolver(
    private val context: Context,
    private val store: SecureStore,
) {
    companion object {
        private const val TAG = "EndpointResolver"
        private const val FAST_PROBE_TIMEOUT_MS = 1200L
        private const val RECOVERY_TIMEOUT_MS = 2000L
    }

    private val mdnsDiscoverer = DesktopDiscoverer(context)
    private val udpDiscoverer = UdpDesktopDiscoverer()

    /**
     * 解析当前可用的可信端点（自动处理快速命中或漫游自愈）。
     */
    suspend fun resolve(paired: PairedDesktop, cached: EndpointCache?): EndpointCache? =
        withContext(Dispatchers.IO) {
            // 1. Fast Path
            if (cached != null) {
                Log.d(TAG, "Testing cached endpoint ${cached.host}:${cached.port}...")
                if (verifyCandidate(cached.host, cached.port, paired, FAST_PROBE_TIMEOUT_MS)) {
                    Log.i(TAG, "Cached endpoint ${cached.host}:${cached.port} is reachable and verified.")
                    return@withContext cached
                }
                Log.w(TAG, "Cached endpoint ${cached.host}:${cached.port} unreachable, initiating recovery...")
            }

            // 2. Recovery Path
            recover(paired)
        }

    /**
     * 显式执行端点发现与自愈流程。
     */
    suspend fun recover(paired: PairedDesktop): EndpointCache? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting endpoint recovery for paired desktop ${paired.deviceId}...")

        // Step A: 尝试 NSD / mDNS 发现
        try {
            val mdnsResult = mdnsDiscoverer.discover(timeoutMs = 1500L)
            if (mdnsResult != null) {
                Log.i(TAG, "mDNS found candidate ${mdnsResult.host}:${mdnsResult.port}")
                if (verifyCandidate(mdnsResult.host, mdnsResult.port, paired, RECOVERY_TIMEOUT_MS)) {
                    Log.i(TAG, "mDNS candidate ${mdnsResult.host}:${mdnsResult.port} verified successfully!")
                    val newEndpoint = EndpointCache(mdnsResult.host, mdnsResult.port)
                    store.saveEndpoint(mdnsResult.host, mdnsResult.port)
                    return@withContext newEndpoint
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "mDNS discovery skipped/failed: ${e.message}")
        }

        // Step B: 尝试 Phone-Link UDP 广播发现 (UDP 8485)
        try {
            val udpResult = udpDiscoverer.discover(paired.deviceId, timeoutMs = 2000L)
            if (udpResult != null) {
                Log.i(TAG, "UDP discoverer found candidate ${udpResult.host}:${udpResult.port}")
                if (verifyCandidate(udpResult.host, udpResult.port, paired, RECOVERY_TIMEOUT_MS)) {
                    Log.i(TAG, "UDP candidate ${udpResult.host}:${udpResult.port} verified successfully!")
                    val newEndpoint = EndpointCache(udpResult.host, udpResult.port)
                    store.saveEndpoint(udpResult.host, udpResult.port)
                    return@withContext newEndpoint
                } else {
                    Log.w(TAG, "UDP candidate ${udpResult.host}:${udpResult.port} FAILED TLS fingerprint verification!")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP discovery failed: ${e.message}")
        }

        Log.w(TAG, "Endpoint recovery exhausted: no trusted desktop found.")
        null
    }

    /**
     * 密码学零信任验证：发起 HTTPS 请求并由 OkHttp 验证证书 SHA-256 指纹。
     */
    suspend fun verifyCandidate(
        host: String,
        port: Int,
        paired: PairedDesktop,
        timeoutMs: Long = 1500L,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseClient = PinnedClientFactory.create(paired.certificateFingerprint)
            val client = baseClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("https://$host:$port/v1/health")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string() ?: return@withContext false
                val json = JSONObject(body)
                val status = json.optString("status", "")
                val respDeviceId = json.optString("deviceId", "")
                if (status != "ok") return@withContext false
                if (respDeviceId.isNotEmpty() && !respDeviceId.equals(paired.deviceId, ignoreCase = true)) {
                    Log.w(TAG, "DeviceId mismatch: expected ${paired.deviceId}, got $respDeviceId")
                    return@withContext false
                }
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Verification probe failed for $host:$port: ${e.message}")
            false
        }
    }
}
