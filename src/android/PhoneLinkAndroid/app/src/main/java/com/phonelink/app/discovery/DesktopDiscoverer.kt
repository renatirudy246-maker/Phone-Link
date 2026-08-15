package com.phonelink.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 一次 NSD 发现的结果。 */
data class DiscoveredService(
    val host: String,
    val port: Int,
    val deviceId: String?,
    val deviceName: String?,
)

/**
 * 通过 Android NSD 发现 `_phonelink._tcp` 桌面服务。
 * TXT 记录（API 30+ 可用）携带 version/deviceId/name，仅用于展示与匹配，不含密钥。
 */
class DesktopDiscoverer(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private val disposed = AtomicBoolean(false)

    suspend fun discover(timeoutMs: Long = 8000): DiscoveredService? =
        suspendCancellableCoroutine { cont ->
            if (cont.isCancelled) return@suspendCancellableCoroutine

            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsd == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            nsdManager = nsd

            val resolver = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed: $errorCode")
                    if (!cont.isCompleted) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD resolved: ${serviceInfo.serviceName} -> ${serviceInfo.host}")

                    val deviceId = if (Build.VERSION.SDK_INT >= 30) {
                        serviceInfo.attributes?.get("deviceId")?.toString(Charsets.UTF_8)
                    } else {
                        null
                    }
                    val deviceName = if (Build.VERSION.SDK_INT >= 30) {
                        serviceInfo.attributes?.get("name")?.toString(Charsets.UTF_8)
                    } else {
                        null
                    }
                    val result = DiscoveredService(
                        host = serviceInfo.host?.hostAddress ?: "",
                        port = serviceInfo.port,
                        deviceId = deviceId,
                        deviceName = deviceName,
                    )
                    if (!cont.isCompleted) cont.resume(result)
                }
            }
            resolveListener = resolver

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "NSD discovery started: $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                    try {
                        nsd.resolveService(serviceInfo, resolver)
                    } catch (e: Exception) {
                        Log.w(TAG, "resolveService failed", e)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD discovery failed: $errorCode")
                    if (!cont.isCompleted) cont.resume(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener

            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.w(TAG, "discoverServices failed", e)
                if (!cont.isCompleted) cont.resume(null)
            }

            cont.invokeOnCancellation {
                teardown()
            }

            // 超时兜底：避免无服务时永久挂起
            Thread {
                Thread.sleep(timeoutMs)
                if (!cont.isCompleted && !disposed.get()) {
                    Log.i(TAG, "NSD discovery timed out after ${timeoutMs}ms")
                    teardown()
                    cont.resume(null)
                }
            }.start()
        }

    private fun teardown() {
        if (disposed.compareAndSet(false, true)) {
            try {
                discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            } catch (_: Exception) {
            }
        }
    }

    fun destroy() {
        disposed.set(true)
        teardown()
        nsdManager = null
        discoveryListener = null
        resolveListener = null
    }

    companion object {
        private const val TAG = "PhoneLinkNSD"
        const val SERVICE_TYPE = "_phonelink._tcp"
    }
}