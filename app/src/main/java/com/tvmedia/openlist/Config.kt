package com.tvmedia.openlist

/**
 * 应用级配置：UA、超时、播放缓冲。
 *
 * **这里没有任何服务器地址或账号。** 本版本只使用夸克网盘，凭证来自扫码登录并加密保存在本机，
 * 不需要硬编码坐标，也不需要用户手填任何东西。
 *
 * 夸克协议的**身份常量**（clientID / signKey / appVer / 设备信息）刻意放在
 * `quark/QuarkProtocol.kt` —— 让协议变更成为单点修改，而不是散落在应用配置里。
 */
object Config {

    /**
     * 桌面 Chrome UA。用在播放请求与预读代理的上游请求上。
     *
     * 夸克 CDN 直链并不挑 UA，带上它是零成本的防御性措施。
     * 注意：**UA 不是卡顿的原因**，不要去调它。
     */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val CONNECT_TIMEOUT_MS: Long = 15_000L
    const val READ_TIMEOUT_MS: Long = 30_000L

    /** ExoPlayer load control: large buffers so TV playback does not stall. */
    const val MIN_BUFFER_MS: Int = 15_000
    const val MAX_BUFFER_MS: Int = 60_000
    const val BUFFER_FOR_PLAYBACK_MS: Int = 2_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 5_000
}
