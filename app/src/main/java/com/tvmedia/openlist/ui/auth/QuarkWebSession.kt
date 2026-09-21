package com.tvmedia.openlist.ui.auth

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.tvmedia.openlist.log.AppLog

/**
 * app 内嵌 WebView 的会话存储清理。
 *
 * 夸克登录是**在 app 的 WebView 里打开的网页版登录页**，所以会话同时存在于两处：
 *
 * 1. `TokenStore`（app 自己加密保存的那串 cookie，用于 API 与代理）；
 * 2. `CookieManager` / `WebStorage`（WebView 自己的存储，登录页下次打开时直接带着它）。
 *
 * 退出登录只清第 1 处是不够的 —— 登录页一打开就带着旧 cookie，页面轮询立刻判定"已登录"，
 * 于是既退不掉也换不了账号。**两处必须一起清**。
 *
 * 必须在主线程调用（WebView 相关 API 的要求）。
 */
object QuarkWebSession {

    /**
     * 清掉 WebView 的全部 cookie（含 session cookie）与 localStorage / sessionStorage。
     *
     * 本项目只用 WebView 打开夸克登录页，所以按域名筛选没有意义，直接全清更彻底。
     * `flush()` 是必要的：只调 `removeAllCookies` 时删除是异步的，进程重启后可能复活。
     *
     * **整体接住异常**：少数电视盒子没有 WebView provider（`QuarkLoginActivity` 里已经为
     * 同一件事做过兜底），那种设备上 `CookieManager.getInstance()` 会直接抛。
     * 退出登录不该因此崩掉 —— 凭证本身由 `TokenStore` 清掉，这才是关键的那一半。
     */
    fun clear() {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }.onFailure { AppLog.w(TAG, "could not clear the webview cookies", it) }
        runCatching { WebStorage.getInstance().deleteAllData() }
            .onFailure { AppLog.w(TAG, "could not clear the webview storage", it) }
    }

    private const val TAG = "QuarkWebSession"
}
