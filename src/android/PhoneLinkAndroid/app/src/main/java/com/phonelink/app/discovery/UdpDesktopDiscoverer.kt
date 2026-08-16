package com.phonelink.app.discovery

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

data class DiscoveredEndpoint(
    val host: String,
    val port: Int,
    val deviceId: String,
)

/**
 * Phone-Link UDP 局域网发现客户端（UDP 8485）。
 *
 * 协议规范：
 * - 广播/单播探测包：PHONELINK_DISCOVER_V1\nnonce=<nonce>\ntargetDeviceId=<targetDeviceId>\n
 * - 响应包：PHONELINK_HERE_V1\nnonce=<nonce>\ndeviceId=<deviceId>\nhttpsPort=<port>\nprotocolVersion=1
 *
 * 关键安全原则：
 * 绝不信任报文中声明的任何 IP 字符串，候选 Host 必须且仅从 DatagramPacket.address.hostAddress 获取。
 * 候选端点仅作为网络位置线索，必须通过后续 TLS 证书 SHA-256 指纹校验才确立信任。
 */
class UdpDesktopDiscoverer {

    companion object {
        private const val TAG = "UdpDiscoverer"
        const val DISCOVERY_PORT = 8485
        private const val REQUEST_HEADER = "PHONELINK_DISCOVER_V1"
        private const val RESPONSE_HEADER = "PHONELINK_HERE_V1"
    }

    suspend fun discover(targetDeviceId: String, timeoutMs: Long = 2000L): DiscoveredEndpoint? =
        withContext(Dispatchers.IO) {
            val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
            val requestText = buildString {
                appendLine(REQUEST_HEADER)
                appendLine("nonce=$nonce")
                appendLine("targetDeviceId=$targetDeviceId")
            }
            val requestBytes = requestText.toByteArray(Charsets.UTF_8)

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = timeoutMs.toInt().coerceAtLeast(500)

                // 收集所有活跃网卡的广播地址及标准 255.255.255.255
                val broadcastTargets = mutableSetOf<InetAddress>()
                try {
                    broadcastTargets.add(InetAddress.getByName("255.255.255.255"))
                } catch (_: Exception) {}

                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val ni = interfaces.nextElement()
                        if (!ni.isUp || ni.isLoopback) continue
                        for (ia in ni.interfaceAddresses) {
                            val bcast = ia.broadcast
                            if (bcast != null) {
                                broadcastTargets.add(bcast)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error enumerating broadcast interfaces: ${e.message}")
                }

                // 发送探测包
                for (target in broadcastTargets) {
                    try {
                        val packet = DatagramPacket(requestBytes, requestBytes.size, target, DISCOVERY_PORT)
                        socket.send(packet)
                        Log.d(TAG, "Sent discovery request to $target:$DISCOVERY_PORT")
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to send discovery to $target: ${e.message}")
                    }
                }

                // 循环接收回复，直到超时或找到匹配 nonce 的合法响应
                val deadline = System.currentTimeMillis() + timeoutMs
                val receiveBuffer = ByteArray(1024)

                while (System.currentTimeMillis() < deadline) {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    try {
                        socket.receive(receivePacket)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }

                    val replyText = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8).trim()
                    val lines = replyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isEmpty() || lines[0] != RESPONSE_HEADER) continue

                    var replyNonce = ""
                    var replyDeviceId = ""
                    var httpsPort = 8484

                    for (i in 1 until lines.size) {
                        val line = lines[i]
                        val eq = line.indexOf('=')
                        if (eq <= 0) continue
                        val k = line.substring(0, eq).trim()
                        val v = line.substring(eq + 1).trim()
                        when (k.lowercase()) {
                            "nonce" -> replyNonce = v
                            "deviceid" -> replyDeviceId = v
                            "httpsport" -> httpsPort = v.toIntOrNull() ?: 8484
                        }
                    }

                    if (replyNonce == nonce && (targetDeviceId.isEmpty() || targetDeviceId == "*" || targetDeviceId.equals(replyDeviceId, ignoreCase = true))) {
                        // 核心安全原则：仅从物理包源地址获取 candidate host
                        val candidateHost = receivePacket.address.hostAddress ?: continue
                        Log.i(TAG, "Discovered desktop candidate at $candidateHost:$httpsPort for device $replyDeviceId")
                        return@withContext DiscoveredEndpoint(
                            host = candidateHost,
                            port = httpsPort,
                            deviceId = replyDeviceId,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP discovery failed: ${e.message}")
            } finally {
                socket?.close()
            }

            null
        }
}
