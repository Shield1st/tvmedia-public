package com.tvmedia.openlist.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 测试用假 CDN：手写的最小 HTTP/1.1 服务器（**零依赖**）。
 *
 * ### 它必须复刻**实测到的真实契约**，否则测试就是自欺欺人
 *
 * 早期版本这个假 CDN"任意 Range 都给全"，于是本地 8/8 全绿，而真机上两个播放器都播不了。
 * 真实链路（2026-09-18 对用户 PC 上运行的 OpenList v4.2.2 + Quark 驱动实测，
 * 34.16 GB 文件，详见任务目录 `research/upstream-measurements.md`）是：
 *
 * 1. **单次响应硬上限 = 10 MiB**。≤10 MiB 的有界请求完整返回；超过就**谎报 `Content-Length`
 *    然后在约 5 MB 处直接掐断 TCP 连接**。
 * 2. **open-ended（`bytes=N-`）最危险**：会声称"整个剩余文件"（36 GB），实际只给 5 MB 就断。
 * 3. **TTFB 恒为 0.23~0.47 s**，与请求范围大小无关。
 * 4. 单个 8 MiB 有界请求 ~10 MB/s；**3 路并发合计 16.4 MB/s**。
 *
 * 所以这个假 CDN 的默认行为就是：**≤[safeRangeBytes] 的有界请求给全，
 * 超出（含 open-ended）就谎报长度然后只给 [truncateLimit] 字节**。
 *
 * 刻意手写而不引入依赖：项目依赖白名单很窄；而 `com.sun.net.httpserver` 在 AGP
 * 单元测试的编译 classpath 里不可见（`android.jar` 是 bootclasspath）。
 */
internal class FakeCdnServer(
    private val body: ByteArray,
    private val contentType: String = "video/mp4",
    /** 实测的"掐断点"：被掐断的响应最多只给这么多字节。 */
    private val truncateLimit: Int = DEFAULT_TRUNCATE_LIMIT,
    /** 超过这个大小的请求会被"谎报 + 掐断"。实测上限 10 MiB。 */
    private val safeRangeBytes: Int = DEFAULT_SAFE_RANGE,
    /** 模拟固定 TTFB（毫秒）。测试默认 0 以保持快。 */
    private val ttfbMillis: Long = 0,
    /** 非 null 时直接回这个错误状态码（模拟直链过期 403 / 404）。 */
    private val errorCode: Int? = null,
    /** false 时**故意不写** `Content-Range`，模拟上游不给总大小。 */
    private val sendContentRange: Boolean = true,
    /** true 时**忽略 `Range`**、永远回 `200` + 整个文件。 */
    private val ignoreRange: Boolean = false,
) {

    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** 收到的上游请求总数。 */
    val requestCount = AtomicInteger()

    /** 每次上游请求的 (起始偏移, 请求字节数)；`length` 为 null 表示 open-ended。 */
    val requests: MutableList<Pair<Long, Long?>> =
        Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())

    val port: Int get() = serverSocket.localPort

    val origin: String get() = "http://127.0.0.1:$port"

    private val acceptor = Thread({ acceptLoop() }, "fake-cdn-accept").apply { isDaemon = true }

    fun start() {
        acceptor.start()
    }

    fun stop() {
        runCatching { serverSocket.close() }
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                return // closed on purpose
            }
            Thread({ handle(socket) }, "fake-cdn-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { open ->
            val input = BufferedInputStream(open.getInputStream())
            val output = BufferedOutputStream(open.getOutputStream())
            while (true) {
                val requestLine = readLine(input) ?: return
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()

                var range: String? = null
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    val colon = header.indexOf(':')
                    if (colon <= 0) continue
                    if (header.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                        range = header.substring(colon + 1).trim()
                    }
                }

                if (ttfbMillis > 0) Thread.sleep(ttfbMillis)
                val keepAlive = writeResponse(output, method, range)
                output.flush()
                if (!keepAlive) return
            }
        }
    }

    /**
     * @return false 表示写完就**关闭连接**（触发"谎报 + 掐断"，或错误响应）。
     */
    private fun writeResponse(output: BufferedOutputStream, method: String, range: String?): Boolean {
        val total = body.size.toLong()

        if (errorCode != null) {
            val payload = "simulated upstream error".toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 $errorCode Error\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            output.write(payload)
            return false
        }

        if (ignoreRange) {
            // 200 + 整个文件；真实服务端同样会在 ~5MB 处掐断。
            val sent = minOf(truncateLimit, body.size)
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $total\r\n" +
                    (if (sendContentRange) "" else "") +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            if (method != "HEAD") output.write(body, 0, sent)
            return sent == body.size
        }

        val parsed = parseRange(range)
        val start = parsed?.first ?: 0L
        val boundedEnd = parsed?.second
        val openEnded = boundedEnd == null

        if (start >= total) {
            output.write(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
            return false
        }

        val lastByte = total - 1
        val end = minOf(boundedEnd ?: lastByte, lastByte)
        val requestedSize = end - start + 1
        requests.add(start to (if (openEnded) null else requestedSize))

        // 实测：open-ended 与"超大请求"都会撞上限 → 谎报完整长度，然后只给 truncateLimit 就断。
        val oversize = requestedSize > safeRangeBytes
        val sent = if (oversize) minOf(truncateLimit.toLong(), requestedSize).toInt() else requestedSize.toInt()

        val head = buildString {
            append("HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            // 关键：**声称的是完整请求长度**，哪怕实际只会给 sent 个字节。
            append("Content-Length: ").append(requestedSize).append("\r\n")
            if (sendContentRange) {
                append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("Connection: keep-alive\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (method != "HEAD") output.write(body, start.toInt(), sent)
        return !oversize
    }

    /** `bytes=N-M` → (N, M)；`bytes=N-` → (N, null)；无 Range → null。 */
    private fun parseRange(range: String?): Pair<Long, Long?>? {
        if (range == null) return null
        val spec = range.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
        val tail = spec.substring(dash + 1).trim()
        return start to tail.toLongOrNull()
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(byte.toChar())
        }
    }

    companion object {
        /** 实测的掐断点：5,241,576 字节（34 GB 文件上稳定复现）。 */
        const val DEFAULT_TRUNCATE_LIMIT = 5_241_576

        /** 实测的单次响应上限：10 MiB。 */
        const val DEFAULT_SAFE_RANGE = 10 * 1024 * 1024
    }
}
