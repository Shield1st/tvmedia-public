package com.tvmedia.openlist.proxy

import android.content.Context
import com.tvmedia.openlist.log.AppLog
import com.tvmedia.openlist.Config

/**
 * Process-wide owner of the loopback stream-through proxy.
 *
 * **两种播放模式都走它**（见 `MainActivity.play()`）：代理负责注入 CDN 要求的
 * Cookie/Referer/UA。只有外部播放器模式额外需要前台服务（那时本 app 在后台，进程会被回收）。
 */
object LocalProxy {

    @Volatile
    private var server: LocalProxyServer? = null

    /** 向后端取数据时的额外请求头（由数据源安装；默认没有）。 */
    @Volatile
    private var upstreamHeaders: () -> Map<String, String> = { emptyMap() }

    /**
     * 安装「向后端（CDN）取数据时要带的额外请求头」。
     *
     * 夸克网页版的直链要求带 Cookie + Referer + UA（缺了会被拒），而这些头只有数据源知道，
     * 所以由 `QuarkSession` 在装配源时安装。
     *
     * 存的是**取当前值的闭包**而不是快照 —— cookie 会被服务端续期，
     * 代理必须用最新的那一份。
     */
    fun installUpstreamHeaders(provider: () -> Map<String, String>) {
        upstreamHeaders = provider
    }

    /** Starts the proxy if needed. Returns its port, or null when it could not start. */
    @Synchronized
    fun ensureStarted(context: Context, foregroundService: Boolean = true): Int? {
        server?.let { return it.port }
        return try {
            val started = LocalProxyServer(Config.USER_AGENT, { upstreamHeaders() })
            started.start()
            server = started
            // 内置播放器模式不用前台服务：app 自己就在前台，代理不会被杀。
            // 只有外部播放器在前台、本 app 在后台时才需要保活。
            if (foregroundService) ProxyForegroundService.start(context)
            AppLog.i(TAG, "stream-through proxy listening on 127.0.0.1:${started.port}")
            started.port
        } catch (t: Throwable) {
            AppLog.w(TAG, "could not start the local proxy", t)
            null
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        AppLog.i(TAG, "stream-through proxy stopped")
    }

    /**
     * Rewrites a playable URL so the player talks to the loopback proxy instead.
     *
     * The CDN host is carried inside the local path (see [ProxyUpstream]) because it cannot be
     * recovered from the path alone. Path and query are preserved byte for byte — the query holds
     * the CDN signature and the path carries the file extension the player sniffs.
     *
     * Returns [url] unchanged when it is not an absolute http(s) URL; the caller then falls back
     * to playing it directly, which is the right behaviour for anything the proxy cannot handle.
     */
    fun toLocalUrl(url: String, port: Int): String {
        val localPath = ProxyUpstream.localPathFor(url) ?: return url
        return "http://$LOOPBACK:$port$localPath"
    }

    private const val TAG = "LocalProxy"
    private const val LOOPBACK = "127.0.0.1"
}
