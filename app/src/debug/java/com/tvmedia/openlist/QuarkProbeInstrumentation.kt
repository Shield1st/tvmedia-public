package com.tvmedia.openlist

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.tvmedia.openlist.data.source.QuarkSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * **只用于 debug 构建**的真机/模拟器探针（见 `app/src/debug/AndroidManifest.xml`）。
 *
 * 它回答一个**必须实测、不能靠文档**的问题：
 *
 * > 夸克 CDN 的直链到底需不需要 `Cookie` / `Referer` / `User-Agent`？
 * > 以及它的真实响应行为是什么（open-ended / 有界 8 MiB / suffix 各自返回什么）？
 *
 * 为什么必须在设备上、用 app 自己的凭证做：直链是带签名的凭证，
 * 只有 app 能解密出 cookie 去换它；而换出来的直链**不能写进日志**，
 * 所以这里只打**测量结果**（状态码、声称长度、实收字节数），不打 URL 的 query。
 *
 * 运行：
 * ```
 * adb shell am instrument -w com.tvmedia.openlist/com.tvmedia.openlist.QuarkProbeInstrumentation
 * ```
 * 结果在 logcat 的 `QuarkProbe` 标签下。
 */
class QuarkProbeInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        Thread {
            try {
                run()
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed", t)
            } finally {
                finish(Activity.RESULT_OK, Bundle())
            }
        }.start()
    }

    private fun run() {
        val context = targetContext
        val api = QuarkSession.apiClient(context)
        Log.i(TAG, "loggedIn=${QuarkSession.isLoggedIn(context)}")

        val fid = findVideoFid(api) ?: run {
            Log.w(TAG, "no video found in the account; nothing to probe")
            return
        }
        val url = runBlocking { api.downloadUrl(fid) }
        Log.i(TAG, "got a direct link: host=${url.substringBefore('/').take(40)} pathLen=${url.length} hasQuery=${url.contains('?')}")

        val headers = api.playbackHeaders()
        Log.i(TAG, "playbackHeaders keys=${headers.keys}")

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // ---- 正确性 ----
        probe(client, "bare   8MiB", url, emptyMap(), "bytes=0-8388607")
        probe(client, "header 8MiB", url, headers, "bytes=0-8388607")
        probe(client, "header open", url, headers, "bytes=0-")
        probe(client, "header suffix", url, headers, "bytes=-65536")
        probe(client, "header noRange", url, headers, null)

        // ---- 钉死限速阈值：多大的**有界**请求开始被限速？ ----
        // 代理只发有界请求，所以阈值决定了分片大小的上限（OpenList 的参考实现用 10 MB）。
        // 每个只读 4 MiB 就断开 —— 被限速的请求按 0.1 MiB/s 算要 40 s，用 25 s 截止封顶。
        val readTarget = 4L * 1024 * 1024
        for (mib in listOf(8, 10, 16, 24, 32, 64, 128)) {
            val size = mib.toLong() * 1024 * 1024
            throughput(client, "bounded ${mib}MiB", url, headers, "bytes=0-${size - 1}", readTarget)
        }
        throughput(client, "open-ended bytes=0-", url, headers, "bytes=0-", readTarget)
        throughput(client, "no Range", url, headers, null, readTarget)
        throughputParallel(client, "3x bounded 8MiB", url, headers, 24L * 1024 * 1024)
    }

    /** 读 [target] 字节并算吞吐（首字节延迟也记下来）。到 [deadlineMillis] 就停（被限速的请求不该拖满）。 */
    private fun throughput(
        client: OkHttpClient,
        label: String,
        url: String,
        headers: Map<String, String>,
        range: String?,
        target: Long,
        deadlineMillis: Long = 25_000,
    ) {
        val builder = Request.Builder().url(url)
        if (range != null) builder.header("Range", range)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val started = System.currentTimeMillis()
        var firstByteAt = 0L
        var received = 0L
        try {
            client.newCall(builder.build()).execute().use { response ->
                val stream = response.body?.byteStream() ?: return
                val buffer = ByteArray(256 * 1024)
                while (received < target) {
                    if (System.currentTimeMillis() - started > deadlineMillis) break
                    val n = stream.read(buffer)
                    if (n <= 0) break
                    if (firstByteAt == 0L) firstByteAt = System.currentTimeMillis() - started
                    received += n
                }
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                val mib = received / (1024.0 * 1024.0)
                val rate = mib * 1000.0 / elapsed
                val note = if (received < target) " (cut by the 25s deadline)" else ""
                Log.i(
                    TAG,
                    "$label -> ${response.code} firstByte=${firstByteAt}ms got=${"%.2f".format(mib)}MiB " +
                        "of ${target / (1024 * 1024)}MiB in ${elapsed}ms = ${"%.2f".format(rate)} MiB/s$note",
                )
            }
        } catch (e: Exception) {
            Log.i(TAG, "$label -> EXC ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 3 路并发各拉 8 MiB，看合计吞吐。 */
    private fun throughputParallel(
        client: OkHttpClient,
        label: String,
        url: String,
        headers: Map<String, String>,
        target: Long,
    ) {
        val started = System.currentTimeMillis()
        val totals = LongArray(3)
        val threads = (0 until 3).map { i ->
            Thread {
                val offset = i * 8L * 1024 * 1024
                val builder = Request.Builder().url(url)
                    .header("Range", "bytes=$offset-${offset + 8 * 1024 * 1024 - 1}")
                headers.forEach { (k, v) -> builder.header(k, v) }
                runCatching {
                    client.newCall(builder.build()).execute().use { r ->
                        val s = r.body?.byteStream() ?: return@use
                        val b = ByteArray(256 * 1024)
                        var got = 0L
                        while (got < 8L * 1024 * 1024) {
                            val n = s.read(b)
                            if (n <= 0) break
                            got += n
                        }
                        totals[i] = got
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
        val mib = totals.sum() / (1024.0 * 1024.0)
        Log.i(TAG, "$label -> total=${mib.toInt()}MiB in ${elapsed}ms = ${"%.2f".format(mib * 1000.0 / elapsed)} MiB/s")
    }

    private fun probe(
        client: OkHttpClient,
        label: String,
        url: String,
        headers: Map<String, String>,
        range: String?,
    ) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (range != null) builder.header("Range", range)
        val started = System.currentTimeMillis()
        try {
            client.newCall(builder.build()).execute().use { response ->
                val claimed = response.header("Content-Length")
                var received = 0L
                val limit = 12L * 1024 * 1024
                try {
                    val stream = response.body?.byteStream()
                    val buffer = ByteArray(256 * 1024)
                    while (received < limit) {
                        val n = stream?.read(buffer) ?: -1
                        if (n <= 0) break
                        received += n
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "$label -> body read ended: ${e.javaClass.simpleName}: ${e.message}")
                }
                val elapsed = System.currentTimeMillis() - started
                Log.i(
                    TAG,
                    "$label -> status=${response.code} claimedCL=$claimed received=$received " +
                        "CR=${response.header("Content-Range")} CT=${response.header("Content-Type")} " +
                        "AR=${response.header("Accept-Ranges")} ${elapsed}ms",
                )
            }
        } catch (e: Exception) {
            Log.i(TAG, "$label -> EXC ${e.javaClass.simpleName}: ${e.message} ${System.currentTimeMillis() - started}ms")
        }
    }

    /** 在账号里找一个视频文件（最多往下走几层目录）。 */
    private fun findVideoFid(api: com.tvmedia.openlist.quark.QuarkApiClient): String? = runBlocking {
        val queue = ArrayDeque(listOf("0" to 0))
        while (queue.isNotEmpty()) {
            val (dirFid, depth) = queue.removeFirst()
            val files = runCatching { api.list(dirFid) }.getOrNull() ?: continue
            files.firstOrNull { it.isVideo && !it.isDir }?.let { return@runBlocking it.fid }
            if (depth < 2) {
                files.filter { it.isDir }.take(3).forEach { queue.addLast(it.fid to depth + 1) }
            }
        }
        null
    }

    private companion object {
        const val TAG = "QuarkProbe"
    }
}
