package com.tvmedia.openlist.proxy

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8

/**
 * 本地代理路径 ↔ 真实上游 URL 的解析。**纯函数，无 Android 依赖**（因此可被单元测试覆盖）。
 *
 * 本版本只有一个数据源（夸克），它的播放直链是**夸克 CDN 上的完整绝对 URL**，
 * host 无法从本地路径反推出来，所以把 origin（`scheme://host[:port]`）编码进路径前缀：
 *
 * ```
 * http://127.0.0.1:<port>/_u/<hex(origin)>/<原始 path>?<原始 query>
 * ```
 *
 * 用 **hex** 而不是 base64url：hex 不含 `/`、`?`、`%`，可以安全地当作单个路径段，
 * 而且 okio 的 `hex()` / `decodeHex()` 成对可用（`decodeBase64Url` 在 okio 3.6 里并不存在）。
 *
 * `path` 与 `query` 保持**逐字节不变** —— 改写它们会破坏 CDN 的签名参数，
 * 或者让播放器因为看不到文件扩展名而选错解复用器。
 *
 * 安全边界：代理只监听 `127.0.0.1`，能构造 `/_u/` 请求的只有本机应用；
 * 即便如此也只接受 `http://` / `https://` 的 origin，不接受其它 scheme。
 */
internal object ProxyUpstream {

    /** origin 前缀。以 `/` 开头与结尾，保证解码出来的 origin 只占一个路径段。 */
    const val ORIGIN_PREFIX: String = "/_u/"

    /** hex(origin) 的长度上限，防止病态输入导致无谓的分配。 */
    private const val MAX_ORIGIN_TOKEN_LENGTH = 512

    /** 该本地路径是否可以被转发（否则代理回 404 并打日志）。 */
    fun isForwardable(localPath: String): Boolean = originAndPath(localPath) != null

    /**
     * 这个绝对 URL 是否**已经是本机代理地址**（`http://127.0.0.1:<port>/_u/...`）。
     *
     * 用来防止"代理套代理"：改写过的地址再包一层，代理就会去请求自己。
     * 真出过这个事故（外部播放器报「此片源无法播放」，因为代理回了 502）。
     */
    fun isLocalProxyUrl(url: String): Boolean {
        val pathStart = url.indexOf('/', url.indexOf("://") + 3).takeIf { it >= 0 } ?: return false
        val path = url.substring(pathStart)
        return isForwardable(path.substringBefore('?'))
    }

    /**
     * 把本地路径解析成真实上游 URL。
     *
     * **拒绝解析指向本机代理自己的地址**：那说明调用方把已经改写过的 URL 又包了一层，
     * 继续下去只会得到 502（真出过这个事故）。
     *
     * @param localPath 不含 query 的本地路径（调用方已按 `?` 切分）
     * @param query 原始 query（不含 `?`），可为空
     * @return null 表示该路径不可转发
     */
    fun resolveUpstream(localPath: String, query: String): String? {
        val (origin, path) = originAndPath(localPath) ?: return null
        val target = origin + path + if (query.isEmpty()) "" else "?$query"
        if (isLocalProxyUrl(target)) return null
        return target
    }

    /**
     * 给一个绝对 URL 生成带 origin 前缀的本地路径（path 与 query 原样保留）。
     *
     * @return null 表示输入不是可用的绝对 http(s) URL
     */
    fun localPathFor(absoluteUrl: String): String? {
        val schemeEnd = absoluteUrl.indexOf("://")
        if (schemeEnd <= 0) return null
        val pathStart = absoluteUrl.indexOf('/', schemeEnd + 3)
        if (pathStart < 0) return null
        val origin = absoluteUrl.substring(0, pathStart)
        return ORIGIN_PREFIX + origin.encodeUtf8().hex() + absoluteUrl.substring(pathStart)
    }

    /** 解析 `/_u/<hex(origin)>/<path>`；非法输入返回 null。 */
    private fun originAndPath(localPath: String): Pair<String, String>? {
        if (!localPath.startsWith(ORIGIN_PREFIX)) return null
        val rest = localPath.substring(ORIGIN_PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash > MAX_ORIGIN_TOKEN_LENGTH) return null
        val origin = try {
            rest.substring(0, slash).decodeHex().utf8()
        } catch (e: IllegalArgumentException) {
            // 非法 hex（例如手改的 URL）→ 当作不可转发，让调用方回 404 并打日志。
            return null
        }
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) return null
        return origin to rest.substring(slash)
    }
}
