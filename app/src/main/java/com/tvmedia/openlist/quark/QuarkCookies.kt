package com.tvmedia.openlist.quark

/**
 * cookie 串的小工具。**纯 JVM，可单元测试**。
 *
 * 夸克的 cookie 是一串 `name=value; name=value; …`（参考实现就是这么整串存/整串用的）。
 * 会话续期靠响应里的 `Set-Cookie: __puus=…` —— 把它合并回原串即可，
 * 不需要理解其它字段。
 */
internal object QuarkCookies {

    /** 读取某个 cookie 的值；不存在返回 null。 */
    fun get(cookie: String, name: String): String? =
        cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    /**
     * 把 `name=value` 写进 cookie 串：已存在则**替换**，不存在则追加。
     *
     * 替换而不是追加很重要 —— 同名 cookie 出现两次时服务端行为不确定。
     */
    fun set(cookie: String, name: String, value: String): String {
        val kept = cookie.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$name=") }
        return (kept + "$name=$value").joinToString("; ")
    }

    /**
     * 合并两个 cookie 串，后者覆盖同名项。
     *
     * 用于：WebView 里两个 host（`pan.quark.cn` / `drive.quark.cn`）各取一次再合成一串。
     */
    fun merge(base: String, extra: String): String {
        var result = base
        for (pair in extra.split(';')) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val name = trimmed.substringBefore('=').trim()
            val value = trimmed.substringAfter('=', "")
            if (name.isEmpty() || value.isEmpty()) continue
            result = set(result, name, value)
        }
        return result
    }

    /** 是否含有会话 cookie（即「看起来已登录」）。 */
    fun hasSession(cookie: String): Boolean =
        get(cookie, QuarkProtocol.COOKIE_SESSION) != null

    /**
     * 从 `Set-Cookie` 头里取出会话 cookie 的新值；没有则返回 null。
     *
     * 只关心 `__puus`：参考实现也只合并它（`__pus` 只在启用转码时才需要，本项目不做转码）。
     */
    fun sessionFromSetCookie(setCookie: String): String? {
        val firstPair = setCookie.substringBefore(';').trim()
        if (!firstPair.startsWith("${QuarkProtocol.COOKIE_SESSION}=")) return null
        return firstPair.substringAfter('=').takeIf { it.isNotBlank() }
    }
}
