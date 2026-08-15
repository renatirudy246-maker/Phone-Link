package com.phonelink.app.network

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * 构建对指定桌面指纹（证书 DER SHA-256，规范 AA:BB:... 格式）钉扎的 OkHttpClient。
 * 仅 HTTPS。信任锚 = 自定义 TrustManager 校验证书指纹，
 * 因此禁用 hostname 校验（自签名证书 CN 是机器名而非 IP，IP 直连场景无意义）。
 * 指纹不匹配抛 SSLHandshakeException，绝不放行。
 */
object PinnedClientFactory {

    fun create(fingerprint: String): OkHttpClient {
        val compact = fingerprint.replace(":", "").replace("-", "").uppercase()
        require(compact.length == 64) { "Invalid fingerprint" }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val presented = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                val hex = presented.joinToString("") { "%02X".format(it) }
                if (!hex.equals(compact, ignoreCase = true)) {
                    throw CertificateException(
                        "Certificate fingerprint mismatch (expected $compact, got $hex)"
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _: String, _: SSLSession -> true }
            .build()
    }
}