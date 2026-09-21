package com.tvmedia.openlist.quark

import okhttp3.Request

/**
 * 假传输层：把预置的响应按顺序发出去，并记录收到的请求。
 *
 * **多出来的请求会抛异常** —— 测试必须显式声明自己期望发几次请求。
 * 否则"多发一次"这类回归会被静默放过。
 */
internal class FakeQuarkTransport(private val responses: List<QuarkResponse>) : QuarkTransport {

    constructor(vararg responses: QuarkResponse) : this(responses.toList())

    val requests = mutableListOf<Request>()
    private var index = 0

    override suspend fun execute(request: Request): QuarkResponse {
        requests += request
        check(index < responses.size) {
            "unexpected request #${index + 1} (${request.method} ${request.url}); " +
                "only ${responses.size} response(s) staged"
        }
        return responses[index++]
    }
}

/** 内存版凭证持有者，模拟 TokenStore（不落盘）。 */
internal class FakeQuarkCookieHolder(
    override var cookie: String = "sid=test; __puus=session-token",
) : QuarkCookieHolder
