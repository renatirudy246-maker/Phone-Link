package com.phonelink.app.discovery

/**
 * 已配对桌面的不可变安全身份（与网络 IP 完全解耦）。
 * 只要密钥指纹和 Token 未变，无论 IP 如何漫游，身份均有效。
 */
data class PairedDesktop(
    val deviceId: String,
    val certificateFingerprint: String,
    val deviceToken: String,
    val desktopName: String,
)

/**
 * 可变网络端点缓存（IP 与端口可能随时因漫游/热点切换而改变）。
 */
data class EndpointCache(
    val host: String,
    val port: Int,
    val lastVerifiedAt: Long = System.currentTimeMillis(),
)
