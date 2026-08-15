package com.phonelink.app.pairing

/**
 * 移动端设备身份。DeviceId 是安装级身份（非秘密）：
 * 首次使用生成并持久化，之后所有重新配对/重连复用同一 ID，
 * 避免每次扫码配对都在桌面端创建重复设备记录。
 */
object DeviceIdentity {

    /** 生成 `mobile-<uuid 无连字符>`（与桌面端配对协议契约一致）。 */
    fun generateMobileDeviceId(): String =
        "mobile-" + java.util.UUID.randomUUID().toString().replace("-", "")
}