package com.tvmedia.openlist.quark

import com.tvmedia.openlist.Config
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一次夸克 HTTP 响应：状态码 + body 字符串 + `Set-Cookie`（用于会话续期）。 */
data class QuarkResponse(
    val code: Int,
    val body: String,
    val setCookies: List<String> = emptyList(),
)

/**
 * 可注入的传输层。
 *
 * 单元测试用假实现替换它，从而在不碰网络的前提下断言请求结构与响应解析。
 */
fun interface QuarkTransport {
    suspend fun execute(request: Request): QuarkResponse
}

/**
 * 夸克专用 HTTP 出口。
 *
 * **刻意不复用任何其它 HTTP 客户端**：夸克用桌面客户端 UA + `Cookie` + `Referer`，
 * 与播放器/代理那套 Chrome UA 的请求完全不同，混在一个拦截器里只会互相干扰。
 * 但「不在 UI/业务层裸建 OkHttpClient」的约定仍然成立 —— 全项目只有这里一个夸克客户端。
 *
 * **不要**给它装 OkHttp 的 `CookieJar`：cookie 由 [QuarkCookieHolder] 加密持久化，
 * 并需要原样交给预读代理去取 CDN（见 `QuarkApiClient.playbackHeaders`），
 * 所以这里刻意保持「无状态传输」，cookie 由调用方显式放进请求头。
 *
 * 本类**不做 JSON 解析**（解析在 [QuarkApiClient] 里），以便单测只关心请求构造。
 */
object QuarkHttpTransport : QuarkTransport {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Config.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(Config.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(Config.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun execute(request: Request): QuarkResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            // 协程取消 → 立刻取消在途请求，不要让它继续占着连接。
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        QuarkProtocolException(
                            "quark http failed: ${request.method} ${request.url.encodedPath}",
                            e,
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        response.use {
                            continuation.resume(
                                QuarkResponse(
                                    code = it.code,
                                    body = it.body?.string().orEmpty(),
                                    setCookies = it.headers("Set-Cookie"),
                                ),
                            )
                        }
                    } catch (e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                QuarkProtocolException(
                                    "quark http body read failed: " +
                                        "${request.method} ${request.url.encodedPath}",
                                    e,
                                ),
                            )
                        }
                    }
                }
            })
        }
}
