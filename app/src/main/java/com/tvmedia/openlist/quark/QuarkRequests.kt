package com.tvmedia.openlist.quark

import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 夸克网页版请求构造。逐行对应参考实现 OpenList v4.2.2
 * `drivers/quark_uc/util.go` 的 `request()`（含 `pr` / `fr` 两个公共 query 参数）。
 *
 * 与 TV 端（已废弃）的区别：没有 `x-pan-*` 签名头，认证完全靠 `Cookie`；
 * 并且必须带 `Referer`，否则网页版接口会拒绝。
 */
object QuarkRequests {

    /** 参考实现里写死的 `Accept`。 */
    private const val ACCEPT = "application/json, text/plain, */*"

    private val JSON = "application/json".toMediaType()

    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(JSON)

    /**
     * 构造一个夸克网页版请求。
     *
     * @param method `GET` / `POST`（大小写敏感）
     * @param path 不带域名的路径，例如 `/file/sort`
     * @param cookie 整串 cookie；未登录时为空（此时请求注定失败，由调用方负责不让它发生）
     * @param extraQuery 业务参数，追加在 `pr` / `fr` 之后
     * @param body JSON body，非空时按 `POST` 发
     */
    fun api(
        method: String,
        path: String,
        cookie: String,
        extraQuery: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): Request {
        val url: HttpUrl = (QuarkProtocol.API_BASE + path).toHttpUrl()
            .newBuilder()
            .addQueryParameter("pr", QuarkProtocol.QUERY_PR)
            .addQueryParameter("fr", QuarkProtocol.QUERY_FR)
            .apply { extraQuery.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()

        val builder = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT)
            .header("Referer", QuarkProtocol.REFERER)
            .header("User-Agent", QuarkProtocol.USER_AGENT)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)

        return when {
            body != null -> builder
                .header("Content-Type", "application/json")
                .method(method, body.toString().toRequestBody(JSON))
                .build()
            method == "GET" || method == "HEAD" -> builder.method(method, null).build()
            else -> builder.method(method, EMPTY_BODY).build()
        }
    }
}
