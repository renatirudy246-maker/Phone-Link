package com.phonelink.app

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonelink.app.discovery.DesktopDiscoverer
import com.phonelink.app.network.PairApi
import com.phonelink.app.network.PairApiException
import com.phonelink.app.network.PinnedClientFactory
import com.phonelink.app.pairing.QrPayload
import com.phonelink.app.pairing.QrPayloadCodec
import com.phonelink.app.pairing.QrPayloadFormatException
import com.phonelink.app.pairing.SecureStore
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HomeUiState {
    data object NoPairing : HomeUiState
    data object Scanning : HomeUiState
    data object Pairing : HomeUiState
    data class Paired(
        val desktopName: String,
        val connection: ConnectionState,
    ) : HomeUiState
    data class Error(
        val message: String,
        val canRetryPair: Boolean,
    ) : HomeUiState
}

enum class ConnectionState {
    Connecting,
    Online,
    Offline,
    Revoked,
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private var discoverer: DesktopDiscoverer? = null
    private var lastPayload: QrPayload? = null

    var uiState by mutableStateOf<HomeUiState>(HomeUiState.NoPairing)
        private set

    val deviceModel: String = android.os.Build.MODEL

    fun hasPairing(): Boolean = store.hasPairing()

    fun start() {
        if (store.hasPairing()) {
            reconnect()
        } else {
            uiState = HomeUiState.NoPairing
        }
    }

    /** 从已保存配对凭据尝试连接（NSD 发现优先，回退上次端点）。 */
    fun reconnect() {
        val token = store.readToken() ?: run { uiState = HomeUiState.NoPairing; return }
        val fingerprint = store.readFingerprint() ?: run { uiState = HomeUiState.NoPairing; return }
        val desktopName = store.readDesktopName() ?: "Desktop"
        uiState = HomeUiState.Paired(desktopName, ConnectionState.Connecting)
        viewModelScope.launch {
            val endpoint = withContext(Dispatchers.IO) {
                discoverer ?: DesktopDiscoverer(getApplication()).also { discoverer = it }
            }
            val found = withContext(Dispatchers.IO) { endpoint.discover() }
            if (found != null) {
                checkHealth(found.host, found.port, fingerprint, token, desktopName)
            } else {
                // 回退：上次配对端点（host/port 非敏感）
                val saved = loadSavedEndpoint()
                if (saved != null) {
                    checkHealth(saved.first, saved.second, fingerprint, token, desktopName)
                } else {
                    uiState = HomeUiState.Error("找不到桌面端（mDNS 发现失败）。", canRetryPair = false)
                }
            }
        }
    }

    /** 扫描到 QR：解析 → POST /v1/pair → 保存凭据 → 连接。 */
    fun onQrDecoded(text: String) {
        uiState = HomeUiState.Pairing
        viewModelScope.launch {
            val payload = try {
                withContext(Dispatchers.Default) { QrPayloadCodec.decode(text) }
            } catch (e: QrPayloadFormatException) {
                Log.e(TAG, "QR parse failed: len=${text.length} head=${text.take(60)} err=${e.message}")
                uiState = HomeUiState.Error("二维码格式无效：${e.message}", canRetryPair = true)
                return@launch
            }

            if (payload.expiresAtEpochMillis <= System.currentTimeMillis()) {
                uiState = HomeUiState.Error("二维码已过期，请在桌面端重新生成。", canRetryPair = true)
                return@launch
            }
            if (payload.protocolVersion != QrPayloadCodec.PROTOCOL_VERSION) {
                uiState = HomeUiState.Error("桌面端协议版本不支持，请升级桌面端。", canRetryPair = true)
                return@launch
            }

            lastPayload = payload
            doPair(payload)
        }
    }

    private fun doPair(payload: QrPayload) {
        uiState = HomeUiState.Pairing
        viewModelScope.launch {
            try {
                val api = withContext(Dispatchers.IO) {
                    PairApi(
                        clientFactory = { fp -> PinnedClientFactory.create(fp) },
                        host = payload.host,
                        port = payload.port,
                        fingerprint = payload.certificateFingerprint,
                        mobileDeviceName = deviceModel,
                    )
                }
                val result = withContext(Dispatchers.IO) {
                    api.pair(payload.oneTimeToken)
                }
                store.save(
                    deviceToken = result.deviceToken,
                    desktopDeviceId = result.desktopDeviceId,
                    desktopDeviceName = payload.desktopDeviceName,
                    certificateFingerprint = payload.certificateFingerprint,
                )
                saveEndpoint(payload.host, payload.port)
                uiState = HomeUiState.Paired(payload.desktopDeviceName, ConnectionState.Online)
            } catch (e: PairApiException) {
                handleApiError(e, payload)
            } catch (e: SSLHandshakeException) {
                Log.e(TAG, "Pin mismatch during pair", e)
                uiState = HomeUiState.Error("证书指纹不匹配，已拒绝连接。请在桌面端重新生成二维码。", canRetryPair = true)
            } catch (e: SSLPeerUnverifiedException) {
                Log.e(TAG, "Pin mismatch during pair", e)
                uiState = HomeUiState.Error("证书指纹不匹配，已拒绝连接。请在桌面端重新生成二维码。", canRetryPair = true)
            } catch (e: SSLException) {
                Log.e(TAG, "TLS failure during pair", e)
                uiState = HomeUiState.Error("TLS 握手失败：${e.message}", canRetryPair = true)
            } catch (e: UnknownHostException) {
                Log.e(TAG, "Unknown host during pair", e)
                uiState = HomeUiState.Error("无法连接桌面端（地址解析失败）。", canRetryPair = true)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "IO failure during pair", e)
                uiState = HomeUiState.Error("无法连接桌面端：${e.message}", canRetryPair = true)
            } catch (e: Exception) {
                Log.e(TAG, "Pair failed", e)
                uiState = HomeUiState.Error("配对失败：${e.message}", canRetryPair = true)
            }
        }
    }

    private fun checkHealth(host: String, port: Int, fingerprint: String, token: String, desktopName: String) {
        uiState = HomeUiState.Paired(desktopName, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val api = withContext(Dispatchers.IO) {
                    PairApi(
                        clientFactory = { fp -> PinnedClientFactory.create(fp) },
                        host = host,
                        port = port,
                        fingerprint = fingerprint,
                        mobileDeviceName = deviceModel,
                    )
                }
                val health = withContext(Dispatchers.IO) { api.health(token) }
                uiState = HomeUiState.Paired(
                    desktopName = health.deviceName.ifBlank { desktopName },
                    connection = ConnectionState.Online,
                )
            } catch (e: PairApiException) {
                handleApiError(e, null)
            } catch (e: SSLHandshakeException) {
                Log.e(TAG, "Pin mismatch during health", e)
                uiState = HomeUiState.Error("桌面证书与已配对指纹不一致，已拒绝连接。请重新配对。", canRetryPair = true)
            } catch (e: SSLPeerUnverifiedException) {
                Log.e(TAG, "Pin mismatch during health", e)
                uiState = HomeUiState.Error("桌面证书与已配对指纹不一致，已拒绝连接。请重新配对。", canRetryPair = true)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "IO failure during health", e)
                uiState = HomeUiState.Paired(desktopName, ConnectionState.Offline)
            } catch (e: Exception) {
                Log.e(TAG, "Health check failed", e)
                uiState = HomeUiState.Paired(desktopName, ConnectionState.Offline)
            }
        }
    }

    private fun handleApiError(e: PairApiException, payload: QrPayload?) {
        when (e.code) {
            "DEVICE_REVOKED" -> {
                store.clear()
                uiState = HomeUiState.Error("该设备已被桌面端撤销，请重新扫码配对。", canRetryPair = true)
            }
            "PAIR_TOKEN_EXPIRED" -> {
                uiState = HomeUiState.Error("配对二维码已过期，请重新生成。", canRetryPair = true)
            }
            "PAIR_ALREADY_USED" -> {
                uiState = HomeUiState.Error("配对二维码已被使用，请重新生成。", canRetryPair = true)
            }
            "PAIR_TOKEN_INVALID" -> {
                uiState = HomeUiState.Error("配对二维码无效，请重新生成。", canRetryPair = true)
            }
            "UNSUPPORTED_PROTOCOL" -> {
                uiState = HomeUiState.Error("协议版本不匹配，请更新桌面端。", canRetryPair = true)
            }
            else -> {
                uiState = HomeUiState.Error("请求失败：${e.message}", canRetryPair = payload != null)
            }
        }
    }

    fun startScan() {
        uiState = HomeUiState.Scanning
    }

    fun cancelScan() {
        uiState = if (store.hasPairing()) HomeUiState.Paired(store.readDesktopName() ?: "Desktop", ConnectionState.Offline) else HomeUiState.NoPairing
    }

    fun clearPairing() {
        store.clear()
        uiState = HomeUiState.NoPairing
    }

    override fun onCleared() {
        discoverer?.destroy()
        discoverer = null
        super.onCleared()
    }

    private fun saveEndpoint(host: String, port: Int) {
        store.saveEndpoint(host, port)
    }

    private fun loadSavedEndpoint(): Pair<String, Int>? = store.readEndpoint()

    companion object {
        private const val TAG = "PhoneLinkVM"
    }
}