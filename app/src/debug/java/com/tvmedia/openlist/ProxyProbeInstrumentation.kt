package com.tvmedia.openlist

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.tvmedia.openlist.data.model.Entry
import com.tvmedia.openlist.data.source.MediaSource
import com.tvmedia.openlist.data.source.QuarkSession
import com.tvmedia.openlist.proxy.LocalProxy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * **只用于 debug 构建**的端到端探针（不进正式包）：
 * 用 app 自己的凭证 + 真实夸克直链，把**回环代理对播放器的响应契约**逐条实测出来。
 *
 * 为什么必须有它：真机（电视）开不了 adb，而"系统播放器播几秒就退出"这类故障
 * **只能靠"代理到底回了什么"来判定**。这里让设备自己把整条链路跑一遍：
 *
 * | 检查 | 断言（播放器看到的东西） |
 * |---|---|
 * | 1 `HEAD` | `200` + `Content-Length` = 文件总大小 |
 * | 2 `bytes=0-`（**播放器默认形式**） | `206` + `Content-Range: bytes 0-(total-1)/total`，`Content-Length` = **剩余全长**（不是一块 8 MiB） |
 * | 3 顺序读 96 MiB（跨 4 个 24 MiB 预读窗口） | 字节与**直连 CDN** 取到的同一段**逐字节一致**（SHA-256） |
 * | 4 `bytes=<32MiB>-<+8MiB>` | `206` + 精确的 `Content-Range`/`Content-Length`，字节与直连一致 |
 * | 5 `bytes=-65536`（suffix） | `206` + 真的给**文件末尾** 64 KiB，字节与直连一致 |
 * | 6 无 `Range` | `200` + `Content-Length` = 总大小（200 的实体长度语义） |
 *
 * 检查 2 就是「播 4 秒就退出」的判据：不读 `Content-Range` 的播放器只认 `Content-Length`，
 * 报一块 8 MiB 就等于告诉它"整个文件只有 8 MiB"。
 *
 * 运行（模拟器 / 已 root 的真机）：
 * ```
 * adb shell am instrument -w com.tvmedia.openlist/com.tvmedia.openlist.ProxyProbeInstrumentation
 * adb logcat -d -s ProxyProbe
 * ```
 */
class ProxyProbeInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        Thread {
            try {
                run()
            } catch (t: Throwable) {
                Log.e(TAG, "proxy probe failed: ${t.message}", t)
            } finally {
                finish(Activity.RESULT_OK, Bundle())
            }
        }.start()
    }

    private fun run() {
        val context = targetContext
        val source = QuarkSession.source(context)
            ?: error("no quark source (not logged in?)")
        val entry = findPlayable(source) ?: error("no playable entry in the account")
        val upstream = runBlocking { source.resolvePlayUrl(entry.path) }
        val headers = source.playbackHeaders()
        Log.i(TAG, "entry=${entry.name} listedSize=${entry.size} headerKeys=${headers.keys}")

        val port = LocalProxy.ensureStarted(context, foregroundService = false)
            ?: error("the proxy did not start")
        val local = LocalProxy.toLocalUrl(upstream, port)
        check(local != upstream) { "toLocalUrl did not rewrite the url" }
        Log.i(TAG, "proxy on 127.0.0.1:$port; probing the contract a player sees")

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val total = referenceTotal(client, upstream, headers)
        Log.i(TAG, "CDN total size = $total bytes")

        var passed = 0
        var failed = 0
        fun verify(name: String, ok: Boolean, detail: String) {
            if (ok) passed++ else failed++
            Log.i(TAG, (if (ok) "PASS " else "FAIL ") + name + " :: " + detail)
        }

        // 1) HEAD
        run {
            val r = request(client, local, "HEAD", null, emptyMap(), 0)
            verify(
                "HEAD",
                r.code == 200 && r.contentLength == total,
                "code=${r.code} cl=${r.contentLength} expected=$total",
            )
        }

        // 2) open-ended：播放器的默认形式 —— 必须声明"剩余全长"而不是一块。
        val probeBytes = 96L * MIB
        val openEnded = request(client, local, "GET", "bytes=0-", emptyMap(), probeBytes)
        verify(
            "open-ended bytes=0-",
            openEnded.code == 206 &&
                openEnded.contentLength == total &&
                openEnded.contentRange == "bytes 0-${total - 1}/$total" &&
                openEnded.bytes == probeBytes,
            "code=${openEnded.code} cl=${openEnded.contentLength} cr=${openEnded.contentRange} " +
                "read=${openEnded.bytes} expectedCl=$total",
        )

        // 3) 逐字节比对：代理读到的 96 MiB 必须与直连 CDN 的同一段完全一致。
        val referenceHead = referenceDigest(client, upstream, headers, 0, probeBytes)
        verify(
            "bytes match direct cdn (0..96MiB)",
            openEnded.digest == referenceHead,
            "proxy=${openEnded.digest.take(16)} cdn=${referenceHead.take(16)} " +
                "rate=${"%.2f".format(openEnded.rateMibPerSecond)} MiB/s",
        )

        // 4) 有界 8 MiB（seek 到 32 MiB）
        val offset = 32L * MIB
        val bounded = request(client, local, "GET", "bytes=$offset-${offset + 8 * MIB - 1}", emptyMap(), 8 * MIB)
        val referenceBounded = referenceDigest(client, upstream, headers, offset, 8 * MIB)
        verify(
            "bounded bytes=32MiB-40MiB",
            bounded.code == 206 &&
                bounded.contentLength == 8 * MIB &&
                bounded.contentRange == "bytes $offset-${offset + 8 * MIB - 1}/$total" &&
                bounded.digest == referenceBounded,
            "code=${bounded.code} cl=${bounded.contentLength} cr=${bounded.contentRange} " +
                "match=${bounded.digest == referenceBounded}",
        )

        // 5) suffix（MKV 的索引在文件末尾，外部播放器一定会问）
        val suffix = request(client, local, "GET", "bytes=-65536", emptyMap(), 65536)
        val referenceSuffix = referenceDigest(client, upstream, headers, total - 65536, 65536)
        verify(
            "suffix bytes=-65536",
            suffix.code == 206 &&
                suffix.contentLength == 65536L &&
                suffix.contentRange == "bytes ${total - 65536}-${total - 1}/$total" &&
                suffix.digest == referenceSuffix,
            "code=${suffix.code} cl=${suffix.contentLength} cr=${suffix.contentRange} " +
                "match=${suffix.digest == referenceSuffix}",
        )

        // 6) 无 Range → 200，实体长度必须是完整文件长度。
        run {
            val r = request(client, local, "GET", null, emptyMap(), MIB)
            verify(
                "no Range -> 200",
                r.code == 200 && r.contentLength == total && r.bytes == MIB,
                "code=${r.code} cl=${r.contentLength} read=${r.bytes} expectedCl=$total",
            )
        }

        Log.i(TAG, "PROXY PROBE: $passed passed / $failed failed")
        check(failed == 0) { "$failed contract check(s) failed" }
    }

    /** 一次请求；[limit] 是**读取上限**（读完就关，模拟播放器只缓冲一段）。 */
    private fun request(
        client: OkHttpClient,
        url: String,
        method: String,
        range: String?,
        headers: Map<String, String>,
        limit: Long,
    ): ProbeResponse {
        val builder = Request.Builder().url(url).method(method, null)
        if (range != null) builder.header("Range", range)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val started = System.currentTimeMillis()
        return client.newCall(builder.build()).execute().use { response ->
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            val stream = response.body?.byteStream()
            if (stream != null) {
                val buffer = ByteArray(256 * 1024)
                while (read < limit) {
                    val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), limit - read).toInt())
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                    read += n
                }
            }
            ProbeResponse(
                code = response.code,
                contentLength = response.header("Content-Length")?.toLongOrNull(),
                contentRange = response.header("Content-Range"),
                bytes = read,
                digest = digest.digest().joinToString("") { "%02x".format(it) },
                elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1),
            )
        }
    }

    /** 直连 CDN 拿文件总大小（1 字节有界请求，靠 `Content-Range`）。 */
    private fun referenceTotal(
        client: OkHttpClient,
        upstream: String,
        headers: Map<String, String>,
    ): Long {
        val builder = Request.Builder().url(upstream).header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute().use { response ->
            check(response.code == 206) { "the CDN answered ${response.code} to a 1-byte range" }
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: error("the CDN sent no usable Content-Range")
        }
    }

    /**
     * 直连 CDN 取 [offset, offset+length) 的 SHA-256。
     *
     * **必须用 ≤8 MiB 的有界请求**：CDN 对"大请求"（open-ended / 无 Range / 1 GB 有界）
     * 限速到 ~0.1 MiB/s（实测），拿它当参考会慢到不可用。
     */
    private fun referenceDigest(
        client: OkHttpClient,
        upstream: String,
        headers: Map<String, String>,
        offset: Long,
        length: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val want = minOf(remaining, 8 * MIB)
            val builder = Request.Builder().url(upstream)
                .header("Range", "bytes=$position-${position + want - 1}")
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                check(response.code == 206) { "cdn reference: HTTP ${response.code} @$position" }
                val stream = response.body?.byteStream() ?: error("cdn reference: no body")
                val buffer = ByteArray(256 * 1024)
                var got = 0L
                while (got < want) {
                    val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), want - got).toInt())
                    check(n > 0) { "cdn reference: short read @$position ($got/$want)" }
                    digest.update(buffer, 0, n)
                    got += n
                }
            }
            position += want
            remaining -= want
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 广度优先找一个可播放条目（目录名路径只有列过才知道，所以要逐层列）。 */
    private fun findPlayable(source: MediaSource): Entry? {
        var frontier = listOf("/")
        repeat(3) {
            val next = mutableListOf<String>()
            for (path in frontier) {
                val entries = runCatching { runBlocking { source.list(path) } }.getOrNull() ?: continue
                entries.firstOrNull { it.isPlayable }?.let { return it }
                entries.filter { it.isDir }.take(4).forEach { next.add(it.path) }
            }
            frontier = next
        }
        return null
    }

    private class ProbeResponse(
        val code: Int,
        val contentLength: Long?,
        val contentRange: String?,
        val bytes: Long,
        val digest: String,
        val elapsedMs: Long,
    ) {
        val rateMibPerSecond: Double get() = bytes / MIB.toDouble() * 1000.0 / elapsedMs
    }

    private companion object {
        const val TAG = "ProxyProbe"
        const val MIB = 1024L * 1024
    }
}
