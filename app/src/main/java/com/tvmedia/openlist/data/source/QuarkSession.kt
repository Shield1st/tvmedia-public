package com.tvmedia.openlist.data.source

import android.content.Context
import com.tvmedia.openlist.log.AppLog
import com.tvmedia.openlist.data.settings.TokenStore
import com.tvmedia.openlist.proxy.LocalProxy
import com.tvmedia.openlist.quark.QuarkApiClient
import com.tvmedia.openlist.quark.QuarkSource

/**
 * 夸克源的进程级装配点：凭证存储 + 协议客户端 + [QuarkSource]。
 *
 * **必须是进程级单例**，因为夸克源持有的状态要跨 Activity 存活：
 *
 * - cookie 会被服务端续期（`__puus`），进程内共享同一份才一致；
 * - [QuarkSource] 的「名字路径 → fid」映射由浏览过程填充。每次进 Activity 重建会丢掉它，
 *   表现就是「目录能列出来，点进去或点播放却报『目录信息已失效』」。
 *
 * 本对象**不抛异常**：任何构造失败都只表示"夸克源当前不可用"，
 * 由 [MediaSourceRegistry] 返回 null，让 UI 去引导重新登录。
 */
object QuarkSession {

    private const val TAG = "QuarkSession"

    @Volatile
    private var tokenStore: TokenStore? = null

    @Volatile
    private var apiClient: QuarkApiClient? = null

    @Volatile
    private var source: QuarkSource? = null

    /** 凭证存储（进程级单例）。 */
    fun tokenStore(context: Context): TokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: TokenStore(context.applicationContext).also { tokenStore = it }
        }

    /** 协议客户端（进程级单例）。登录页用它探活 cookie。 */
    fun apiClient(context: Context): QuarkApiClient =
        apiClient ?: synchronized(this) {
            apiClient ?: QuarkApiClient(tokenStore(context)).also { apiClient = it }
        }

    /** 已登录时返回夸克源；未登录或构造失败时返回 null。 */
    fun source(context: Context): QuarkSource? {
        source?.let { return it }
        return synchronized(this) {
            source ?: buildSource(context)?.also { source = it }
        }
    }

    fun isLoggedIn(context: Context): Boolean = tokenStore(context).isLoggedIn

    /** 登录成功或登出后调用：丢弃缓存的源，让下一次 [source] 用新凭证重建。 */
    fun onCookieChanged() {
        synchronized(this) { source = null }
    }

    /** 退出登录：清除凭证并丢弃源。 */
    fun logout(context: Context) {
        tokenStore(context).logout()
        synchronized(this) { source = null }
    }

    private fun buildSource(context: Context): QuarkSource? {
        val store = tokenStore(context)
        if (!store.isLoggedIn) return null
        return try {
            val api = apiClient(context)
            // 预读代理向后端（CDN）取数据时必须带上 Cookie/Referer/UA —— 网页版直链会校验它们。
            // 这里装的是「取当前 cookie」的闭包（而不是当时的快照），所以续期后的新 cookie 也会生效。
            LocalProxy.installUpstreamHeaders { api.playbackHeaders() }
            QuarkSource(api)
        } catch (e: Exception) {
            // 构造失败也只是"不可用"，交给调用方引导重新登录。
            AppLog.w(TAG, "could not build the quark source", e)
            null
        }
    }
}
