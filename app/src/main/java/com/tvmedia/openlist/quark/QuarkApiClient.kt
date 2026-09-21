package com.tvmedia.openlist.quark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 夸克数据能力客户端（**网页版**）：列目录、取播放直链、会话校验。
 *
 * 逐行对应参考实现 OpenList v4.2.2 `drivers/quark_uc/`：
 *
 * | 本类方法 | 参考实现 |
 * |---|---|
 * | [list] | `util.go` 的 `GetFiles()`（`GET /file/sort`） |
 * | [downloadUrl] | `util.go` 的 `getDownloadLink()`（`POST /file/download`） |
 * | [verifySession] | 登录后的一次探活（参考实现靠驱动自身的 `Init`） |
 * | 会话续期 | `util.go` `request()` 里合并 `Set-Cookie: __puus` 的那段 |
 *
 * 只取**源视频**直链，不做转码（参考实现的 `use_transcoding_address` 那套不实现）。
 *
 * 本类不引用任何 Android API，单元测试用假 [QuarkTransport] 即可完全覆盖。
 */
class QuarkApiClient(
    private val cookies: QuarkCookieHolder,
    private val transport: QuarkTransport = QuarkHttpTransport,
) {

    /**
     * 列出 [fid] 下的条目；根目录用 [QuarkSource.ROOT_FID]。
     *
     * 分页照搬参考实现：`_size=100`，从 `_page=1` 开始，
     * 直到 `page * size >= metadata._total` 为止。
     */
    suspend fun list(fid: String): List<QuarkFile> {
        val result = mutableListOf<QuarkFile>()
        var page = 1
        while (true) {
            val json = request(
                method = "GET",
                path = PATH_SORT,
                query = mapOf(
                    "pdir_fid" to fid,
                    "_size" to PAGE_SIZE.toString(),
                    "_fetch_total" to "1",
                    "fetch_all_file" to "1",
                    "fetch_risk_file_name" to "1",
                    "_page" to page.toString(),
                ),
            )
            val array = json.jsonObjectOrNull("data")?.jsonArrayOrNull("list")
            val files = if (array == null) {
                emptyList()
            } else {
                array.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.let(QuarkFile::from)
                }
            }
            result += files
            // 空页即停：即使 _total 不可信，循环也一定会结束。
            if (files.isEmpty()) break
            val total = json.jsonObjectOrNull("metadata")?.longOrZero("_total") ?: 0L
            if (page.toLong() * PAGE_SIZE >= total) break
            page++
        }
        return result
    }

    /**
     * 取 [fid] 的播放直链。
     *
     * 返回的是夸克 CDN 上的**完整绝对 URL**；取它的时候**必须带上 [playbackHeaders]**
     * （Cookie + Referer + UA），否则 CDN 会拒绝 —— 这是网页版与 TV 端最关键的差异。
     */
    suspend fun downloadUrl(fid: String): String {
        val body = JsonObject().apply {
            add("fids", JsonArray().apply { add(fid) })
        }
        val json = request(method = "POST", path = PATH_DOWNLOAD, body = body)
        val url = json.jsonArrayOrNull("data")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.stringOrEmpty("download_url")
        return url?.takeIf { it.isNotBlank() }
            ?: throw QuarkProtocolException("夸克未返回下载直链（fid=$fid）")
    }

    /**
     * 探活：用一次最小列目录确认 cookie 真的能用。
     *
     * 登录页抓到 cookie 之后必须调它 —— 抓到 cookie 不等于 cookie 有效
     * （用户可能扫了一半就退出，或 cookie 已被服务端吊销）。
     *
     * 失败时抛 [QuarkProtocolException]，**不吞异常**：调用方需要知道原因。
     */
    suspend fun verifySession() {
        list(QuarkSource.ROOT_FID)
    }

    /**
     * 取播放直链时要带的请求头。
     *
     * 预读代理与内置播放器都要用它去取 CDN；缺任何一个都可能被拒。
     */
    fun playbackHeaders(): Map<String, String> = buildMap {
        put("User-Agent", QuarkProtocol.USER_AGENT)
        put("Referer", QuarkProtocol.REFERER)
        if (cookies.cookie.isNotBlank()) put("Cookie", cookies.cookie)
    }

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): JsonObject {
        val request = QuarkRequests.api(
            method = method,
            path = path,
            cookie = cookies.cookie,
            extraQuery = query,
            body = body,
        )
        val response = transport.execute(request)
        renewSession(response.setCookies)

        val json = parse(response)
        // 参考实现：`if e.Status >= 400 || e.Code != 0 { return errors.New(e.Message) }`
        if (json.intOrZero("status") >= 400 || json.intOrZero("code") != 0) {
            throw QuarkProtocolException(json.errorInfo(contextFor(path)))
        }
        return json
    }

    /**
     * 会话续期：服务端每次响应可能下发新的 `__puus`，合并回 cookie 串并写盘。
     *
     * 这是网页版能长期免重新登录的关键（参考实现同样这么做）。
     */
    private fun renewSession(setCookies: List<String>) {
        for (header in setCookies) {
            val value = QuarkCookies.sessionFromSetCookie(header) ?: continue
            cookies.cookie = QuarkCookies.set(cookies.cookie, QuarkProtocol.COOKIE_SESSION, value)
        }
    }

    private fun parse(response: QuarkResponse): JsonObject =
        try {
            JsonParser.parseString(response.body).asJsonObject
        } catch (e: Exception) {
            // 不回显 body（可能含凭证），但必须带上 HTTP 状态码 ——
            // 被网关/WAF 拦住时没有状态码就只能靠猜。
            throw QuarkProtocolException(
                "quark: not a json response (HTTP ${response.code})",
                e,
            )
        }

    private fun contextFor(path: String): String = when (path) {
        PATH_SORT -> "file/sort"
        PATH_DOWNLOAD -> "file/download"
        else -> path
    }

    private companion object {
        const val PATH_SORT = "/file/sort"
        const val PATH_DOWNLOAD = "/file/download"
        const val PAGE_SIZE = 100
    }
}
