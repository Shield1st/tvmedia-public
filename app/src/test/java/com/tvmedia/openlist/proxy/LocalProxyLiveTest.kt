package com.tvmedia.openlist.proxy

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * **对真实上游的可选验证**（默认跳过）。
 *
 * 假 CDN 再像也还是假的；这个测试直接让 [LocalProxyServer] 对着**真的网盘直链**取数据，
 * 逐字节比对，用来回答"代理到底能不能喂饱播放器"。
 *
 * 运行方式（不需要改构建脚本，`Test` task 继承环境变量）：
 *
 * ```bash
 * TVMEDIA_LIVE_URL='https://<cdn>/<path>?<sign>' \
 *   ./gradlew testDebugUnitTest --tests "com.tvmedia.openlist.proxy.LocalProxyLiveTest"
 * ```
 *
 * 没有设 `TVMEDIA_LIVE_URL` 时它直接返回（不算失败），所以可以安全地留在测试套件里。
 *
 * 注意：直链是**带签名的凭证**，只从环境变量读，**不落盘、不写进日志**。
 */
class LocalProxyLiveTest {

    @Test
    fun `serves a real upstream byte for byte`() {
        val upstream = System.getenv("TVMEDIA_LIVE_URL")?.takeIf { it.isNotBlank() } ?: run {
            println("[live] skipped: set TVMEDIA_LIVE_URL to run this against a real backend")
            return
        }
        val readBytes = (System.getenv("TVMEDIA_LIVE_BYTES")?.toLongOrNull() ?: DEFAULT_READ_BYTES)
        val skipVerify = System.getenv("TVMEDIA_LIVE_SKIP_VERIFY") == "1"
        val reference = referenceReader()

        val proxy = LocalProxyServer(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        )
        proxy.start()
        try {
            val localPath = ProxyUpstream.localPathFor(upstream)
                ?: error("TVMEDIA_LIVE_URL is not a plain http(s) url")
            val localUrl = "http://127.0.0.1:${proxy.port}$localPath"

            // 按真实播放器的形式发 open-ended 请求，反复读，直到拿够 readBytes。
            var position = 0L
            val started = System.currentTimeMillis()
            while (position < readBytes) {
                val response = reference.newCall(
                    Request.Builder().url(localUrl).header("Range", "bytes=$position-").build(),
                ).execute()
                assertEquals("unexpected status at $position", 206, response.code)
                val body = response.body?.bytes() ?: ByteArray(0)
                assertTrue("empty body at $position", body.isNotEmpty())

                // 上游契约：代理对播放器说的长度必须等于实际给的字节数。
                assertEquals(
                    "Content-Length disagrees with the bytes sent at $position",
                    body.size.toString(),
                    response.header("Content-Length"),
                )

                // 逐字节对照"直接向上游取同一段"的结果。
                // 量吞吐时把它关掉（TVMEDIA_LIVE_SKIP_VERIFY=1），否则会多一倍网络开销。
                if (!skipVerify) {
                    val expected = directFetch(reference, upstream, position, body.size)
                    assertEquals("byte count mismatch at $position", expected.size, body.size)
                    for (i in body.indices) {
                        if (body[i] != expected[i]) {
                            throw AssertionError("byte mismatch at ${position + i}")
                        }
                    }
                }
                position += body.size
            }
            val elapsed = System.currentTimeMillis() - started
            println(
                "[live] ok: ${position / MIB} MiB verified in ${elapsed}ms " +
                    "(${position * 1000 / (elapsed.coerceAtLeast(1)) / MIB} MiB/s), " +
                    "requested ${readBytes / MIB} MiB",
            )
        } finally {
            proxy.stop()
        }
    }

    /** 直接向上游取 [length] 字节（有界请求，实测 ≤10 MiB 才可靠）。 */
    private fun directFetch(client: OkHttpClient, url: String, offset: Long, length: Int): ByteArray {
        val want = minOf(length.toLong(), DIRECT_CHUNK).toInt()
        val response = client.newCall(
            Request.Builder().url(url).header("Range", "bytes=$offset-${offset + want - 1}").build(),
        ).execute()
        return response.use { it.body?.bytes() ?: ByteArray(0) }
    }

    private fun referenceReader(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val MIB = 1024L * 1024
        const val DEFAULT_READ_BYTES = 24L * 1024 * 1024
        const val DIRECT_CHUNK = 8L * 1024 * 1024
    }
}
