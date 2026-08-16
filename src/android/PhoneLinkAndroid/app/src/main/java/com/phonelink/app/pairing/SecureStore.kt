package com.phonelink.app.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对凭据的加密存储：Android Keystore 生成的 AES-256-GCM 密钥加密，
 * 密文（IV 前缀）存 SharedPreferences。密钥不可导出，仅本应用可用。
 * 重新配对时旧密钥轮换（先删后建，避免 GCM 计数器复用风险）。
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyAlias = KEY_ALIAS

    /** 是否已安全保存配对凭据。 */
    fun hasPairing(): Boolean = prefs.contains(KEY_TOKEN)

    fun save(
        deviceToken: String,
        desktopDeviceId: String,
        desktopDeviceName: String,
        certificateFingerprint: String,
    ) {
        deleteKey() // 轮换密钥，防止 GCM nonce 复用
        val editor = prefs.edit()
        editor.putString(KEY_TOKEN, encrypt(deviceToken))
        editor.putString(KEY_DESKTOP_ID, encrypt(desktopDeviceId))
        editor.putString(KEY_DESKTOP_NAME, encrypt(desktopDeviceName))
        editor.putString(KEY_FINGERPRINT, encrypt(certificateFingerprint))
        editor.apply()
    }

    fun readToken(): String? = decrypt(prefs.getString(KEY_TOKEN, null))

    fun readDesktopId(): String? = decrypt(prefs.getString(KEY_DESKTOP_ID, null))

    fun readDesktopName(): String? = decrypt(prefs.getString(KEY_DESKTOP_NAME, null))

    fun readFingerprint(): String? = decrypt(prefs.getString(KEY_FINGERPRINT, null))

    fun readPairedDesktop(): com.phonelink.app.discovery.PairedDesktop? {
        val token = readToken() ?: return null
        val deviceId = readDesktopId() ?: return null
        val fingerprint = readFingerprint() ?: return null
        val desktopName = readDesktopName() ?: "Desktop"
        return com.phonelink.app.discovery.PairedDesktop(deviceId, fingerprint, token, desktopName)
    }

    /** 端点（host/port）非敏感，明文保存用于 NSD 失败时回退。 */
    fun saveEndpoint(host: String, port: Int) {
        prefs.edit().putString(KEY_ENDPOINT_HOST, host).putInt(KEY_ENDPOINT_PORT, port).apply()
    }

    fun readEndpoint(): Pair<String, Int>? {
        val host = prefs.getString(KEY_ENDPOINT_HOST, null) ?: return null
        val port = prefs.getInt(KEY_ENDPOINT_PORT, 0)
        if (port !in 1..65535) return null
        return host to port
    }

    fun readEndpointCache(): com.phonelink.app.discovery.EndpointCache? {
        val ep = readEndpoint() ?: return null
        return com.phonelink.app.discovery.EndpointCache(ep.first, ep.second)
    }

    /**
     * 安装级稳定 DeviceId：首次调用生成并持久化，之后复用。
     * 明文存储（DeviceId 非凭据，仅标识），解除配对/重新配对不清除。
     */
    fun getOrCreateDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = DeviceIdentity.generateMobileDeviceId()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun clear() {
        // 注意：KEY_DEVICE_ID 不清除——解除配对只清除配对凭据，
        // 安装级 DeviceId 保持不变，重新配对继续复用同一身份。
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DESKTOP_ID)
            .remove(KEY_DESKTOP_NAME)
            .remove(KEY_FINGERPRINT)
            .apply()
        deleteKey()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // 无密钥时删除失败可忽略
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String? {
        if (encoded == null) return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val cipherText = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null // 密钥已失效（如系统重置 keystore）→ 视为未配对
        }
    }

    companion object {
        private const val PREFS_NAME = "phonelink_secure"
        private const val KEY_ALIAS = "phonelink_pairing_key"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_DESKTOP_ID = "desktop_device_id"
        private const val KEY_DESKTOP_NAME = "desktop_device_name"
        private const val KEY_FINGERPRINT = "certificate_fingerprint"
        private const val KEY_ENDPOINT_HOST = "endpoint_host"
        private const val KEY_ENDPOINT_PORT = "endpoint_port"
        private const val KEY_DEVICE_ID = "device_id"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}