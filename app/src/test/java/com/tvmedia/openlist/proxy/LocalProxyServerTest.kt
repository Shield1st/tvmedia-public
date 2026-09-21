package com.tvmedia.openlist.proxy

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 端到端验收测试：**假 CDN → LocalProxyServer → 假播放器**。
 *
 * 真机（电视盒子）开不了 adb，播放回归只能靠"装包→肉眼观察→手工导日志"定位，一轮代价极大。
 * 这里把同一条链路搬进 JVM，并且 [FakeCdnServer] 复刻了**实测到的真实上游契约**
 * （单次响应 10 MiB 上限、超出就谎报 `Content-Length` 再掐断、open-ended 最危险、TTFB 0.3s）。
 *
 * **这条约束是血的教训**：早期的假 CDN"任意 Range 都给全"，于是本地 8/8 全绿，
 * 真机上两个播放器都播不了。假上游必须像真的那样"坏"，测试才有意义。
 */
class LocalProxyServerTest {

    private lateinit var source: ByteArray
    private var cdn: FakeCdnServer? = null
    private var proxy: LocalProxyServer? = null
    private val player = OkHttpClient()

    @Before
    fun setUp() {
        // 确定性内容：非零且逐字节可区分，错位 / 空洞一定能被发现。
        source = ByteArray(FILE_SIZE) { (it % 251 + 1).toByte() }
    }

    @After
    fun tearDown() {
        proxy?.let { runCatching { it.stop() } }
        cdn?.let { it.stop() }
    }

    // --- 核心：真的能把整个文件喂给播放器 -----------------------------------------

    /** 播放器发 open-ended（真实播放器的默认形式），必须逐字节读完整个文件。 */
    @Test
    fun `serves the whole file byte for byte to an open-ended reader`() {
        start()
        val read = readSequentially(from = 0L, until = FILE_SIZE.toLong())
        assertEquals("did not reach the end of the file", FILE_SIZE.toLong(), read)
    }

    /** 系统播放器式的 64KB bounded 读，同样必须逐字节正确。 */
    @Test
    fun `serves small bounded reads like a system player`() {
        start()
        var position = 0L
        val until = 8L * 1024 * 1024
        while (position < until) {
            val end = minOf(position + SMALL_READ_BYTES - 1, until - 1)
            playerCall(position, end).use { response ->
                assertEquals("unexpected status at $position", 206, response.code)
                val body = response.body?.bytes() ?: ByteArray(0)
                assertEquals("wrong length at $position", (end - position + 1).toInt(), body.size)
                assertBytesEqual(body, position)
                position += body.size
            }
        }
        assertEquals(until, position)
    }

    /**
     * **最关键的约束**：代理绝不能把播放器的 open-ended 请求原样转给上游。
     *
     * 实测：上游对 `bytes=N-` 会声称"整个剩余文件"（36 GB），然后只给 5 MB 就掐断连接。
     * 原样转发 = 告诉播放器"还有 36 GB"，只给 5 MB 就断 → 必然播不了。
     */
    @Test
    fun `never sends an open-ended range upstream`() {
        start()
        readSequentially(from = 0L, until = 8L * 1024 * 1024)
        val openEnded = cdn!!.requests.filter { it.second == null }
        assertTrue("the proxy forwarded an open-ended range upstream: $openEnded", openEnded.isEmpty())
    }

    /** 每个上游请求都必须 ≤ 10 MiB（实测上限），否则会撞上"谎报 + 掐断"。 */
    @Test
    fun `keeps every upstream request within the measured limit`() {
        start()
        readSequentially(from = 0L, until = FILE_SIZE.toLong())
        val tooBig = cdn!!.requests.filter { (it.second ?: Long.MAX_VALUE) > SAFE_RANGE_BYTES }
        assertTrue("upstream request exceeded the 10MiB limit: $tooBig", tooBig.isEmpty())
    }

    /**
     * **断点续拉**：把上游上限压到 1 MiB，逼每一次响应都被掐断。
     * 代理必须从断点继续拉，而不是把"提前结束"当成文件结束。
     *
     * 这正是 `3aeb334`「播 5 秒卡 5 秒」的真因：它把 `produced < length` 当成 EOF，
     * 于是窗口被截断、读位置之后出现空洞。
     */
    @Test
    fun `resumes from the breakpoint when the upstream caps every response`() {
        start(safeRangeBytes = 1 * 1024 * 1024)
        val read = readSequentially(from = 0L, until = FILE_SIZE.toLong())
        assertEquals("short reads must not be mistaken for EOF", FILE_SIZE.toLong(), read)
    }

    /** seek 到文件中后部再继续读，数据必须仍然正确。 */
    @Test
    fun `serves data correctly after a seek`() {
        start()
        readSequentially(from = 0L, until = 4L * 1024 * 1024)
        val target = 20L * 1024 * 1024
        val read = readSequentially(from = target, until = FILE_SIZE.toLong())
        assertEquals(FILE_SIZE.toLong(), read)
    }

    /** 代理对播放器**从不说谎**：`Content-Length` 必须等于实际写出的字节数。 */
    @Test
    fun `never lies about the length it sends to the player`() {
        start()
        var position = 0L
        repeat(MAX_PLAYER_REQUESTS) {
            if (position >= FILE_SIZE) return
            playerCall(position, null).use { response ->
                assertEquals("unexpected status at $position", 206, response.code)
                val body = response.body?.bytes() ?: ByteArray(0)
                assertTrue("empty body at $position", body.isNotEmpty())
                assertEquals(
                    "Content-Length disagrees with the bytes actually sent at $position",
                    body.size.toString(),
                    response.header("Content-Length"),
                )
                assertEquals(
                    "wrong total size reported at $position",
                    FILE_SIZE.toString(),
                    response.header("Content-Range")?.substringAfterLast('/'),
                )
                assertBytesEqual(body, position)
                position += body.size
            }
        }
    }

    /** 顺序读一个 40MB 文件不该把同一区间抓很多遍（上限宽松，出现乒乓重抓会立刻暴露）。 */
    @Test
    fun `does not refetch the same ranges over and over`() {
        start()
        readSequentially(from = 0L, until = FILE_SIZE.toLong())
        val count = cdn!!.requestCount.get()
        assertTrue("upstream was hit $count times for a 40MB file", count <= MAX_UPSTREAM_REQUESTS)
    }

    /**
     * 极端情况：上游回了 `206` 却**一个字节都不给**就断。
     *
     * 关键是要**快回答**（错误总比无限加载好）—— 真机上"无限加载中"就是这一类。
     */
    @Test
    fun `answers instead of hanging when the upstream sends nothing at all`() {
        start(safeRangeBytes = 1, truncateLimit = 0)
        val started = System.currentTimeMillis()
        val status = runCatching { playerCall(0, null).use { it.code } }.getOrElse { 0 }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("stalled for ${elapsed}ms", elapsed < 20_000)
        assertTrue("expected an error rather than data, got $status", status == 0 || status >= 400)
    }

    /**
     * 窗口不是分段整数倍时，最后一个分段是**短段**（20 MiB 窗口 = 8+8+4）。
     * 取边界写错就会读出未初始化的零字节，或者在一个填不满的分段上死等。
     */
    @Test
    fun `handles a window whose last segment is shorter than the others`() {
        start(windowBytes = 20 * 1024 * 1024, segmentBytes = 8 * 1024 * 1024)
        val read = readSequentially(from = 0L, until = FILE_SIZE.toLong())
        assertEquals(FILE_SIZE.toLong(), read)
    }

    /**
     * **没有 `Range` 的请求必须回 `200`**（而不是 `206`）。
     *
     * 外部播放器常常先发一个普通 GET 探测；对它回 `206 Partial Content` 是违反 HTTP 的，
     * 很多播放器会直接报错（用户看到的就是"未找到文件"）。内置 ExoPlayer 总是带 Range，
     * 所以只有外部播放器会撞上这个 —— 正好对得上"内置好、系统播放器挂"。
     */
    @Test
    fun `answers a request without a range header with 200`() {
        start()
        val response = player.newCall(
            Request.Builder().url("http://127.0.0.1:${proxy!!.port}$mediaPath").build(),
        ).execute()
        response.use {
            assertEquals("a plain GET must not get a 206", 200, it.code)
            assertEquals("wrong total size", FILE_SIZE.toString(), it.header("Content-Length"))
            // **读满 4096 字节，而不是"一次 read 就该返回 4096"**：代理是"有数据就先发"，
            // 一次 read 拿到的可能只是刚到达的那一段（实测出现过 2896 字节），
            // 这属于 TCP 分段，不是契约。契约是：200 + 完整实体长度 + 开头字节正确。
            val first = ByteArray(4096)
            var got = 0
            val stream = it.body?.byteStream() ?: error("no body")
            while (got < first.size) {
                val n = stream.read(first, got, first.size - got)
                if (n <= 0) break
                got += n
            }
            assertEquals("the 200 body ended early", first.size, got)
            assertBytesEqual(first, 0)
        }
    }

    /**
     * **suffix range（`bytes=-N`，要文件末尾 N 字节）必须真的给末尾**。
     *
     * MKV 的索引（Cues / SeekHead）在文件末尾，外部播放器探测末尾是常规操作。
     * 把它当成"从头开始"会让播放器拿到错的数据 → 报"未找到文件"。
     */
    @Test
    fun `serves the last bytes for a suffix range`() {
        start()
        val suffix = 64 * 1024
        player.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${proxy!!.port}$mediaPath")
                .header("Range", "bytes=-$suffix")
                .build(),
        ).execute().use { response ->
            assertEquals("expected 206", 206, response.code)
            assertEquals(
                "suffix range must resolve to the end of the file",
                "bytes ${FILE_SIZE - suffix}-${FILE_SIZE - 1}/$FILE_SIZE",
                response.header("Content-Range"),
            )
            val body = response.body?.bytes() ?: ByteArray(0)
            assertEquals(suffix, body.size)
            assertBytesEqual(body, (FILE_SIZE - suffix).toLong())
        }
    }

    /** `Content-Type` 必须用上游给的（本项目多是 MKV，写死 `video/mp4` 是错的）。 */
    @Test
    fun `forwards the upstream content type`() {
        start(contentType = "video/x-matroska")
        playerCall(0, null).use { response ->
            assertEquals("video/x-matroska", response.header("Content-Type"))
        }
    }

    /**
     * **首字节要快**：代理不能等满一整块再回 —— 真机日志里首字节等了 5.1 秒。
     *
     * 上游单次响应会被掉线，所以“先抓好 8MB 再发给播放器”会让起播（以及每次重连）
     * 都先卡几秒。正确做法是**边收边发**：有了一小块就先发出去。
     */
    @Test
    fun `sends the first bytes before the whole chunk is ready`() {
        start(ttfbMillis = 0)
        val started = System.currentTimeMillis()
        playerCall(0, null).use { response ->
            assertEquals(206, response.code)
            val stream = response.body?.byteStream()
            val head = ByteArray(64 * 1024)
            var got = 0
            // 只读到 64 KiB 就计时：这模拟播放器“拿到第一块就能起播”。
            while (got < head.size) {
                val n = stream!!.read(head, got, head.size - got)
                if (n <= 0) break
                got += n
            }
            val elapsed = System.currentTimeMillis() - started
            assertEquals("expected a first chunk", head.size, got)
            assertTrue("first 64 KiB took ${elapsed}ms -- must not wait for a whole 8 MiB chunk", elapsed < 2_000)
        }
    }

    /**
     * open-ended（`bytes=N-`）的 `Content-Length` 必须是**真实剩余长度**。
     *
     * 早期实现回一块 8 MiB 并把它当作 `Content-Length`；**不看 `Content-Range` 的播放器**
     * 会以为整个文件就只有 8 MiB，播完就“到末尾”退出（真机上是 4 秒左右）。
     * 真正的流媒体服务器（如 OpenList 的 `/d`）给的就是剩余全长，边收边发。
     */
    @Test
    fun `an open-ended range reports the real remaining length`() {
        start()
        playerCall(0, null).use { response ->
            assertEquals(206, response.code)
            assertEquals(
                "open-ended must advertise the whole remainder, not one chunk",
                FILE_SIZE.toString(),
                response.header("Content-Length"),
            )
            assertEquals(
                "Content-Range must span to the end of the file",
                "bytes 0-${FILE_SIZE - 1}/$FILE_SIZE",
                response.header("Content-Range"),
            )
            // 仍然要真的把那么多字节流出来（不能只声明不写）。
            val body = response.body?.bytes() ?: ByteArray(0)
            assertEquals(FILE_SIZE, body.size)
            assertBytesEqual(body, 0L)
        }
    }

    /** 有界请求（`bytes=N-M`）仍按请求的区间原样服务，不能扩张。 */
    @Test
    fun `a bounded range is still served exactly`() {
        start()
        val start = 1024L
        val end = start + 65535
        playerCall(start, end).use { response ->
            assertEquals(206, response.code)
            assertEquals("65536", response.header("Content-Length"))
            assertEquals("bytes $start-$end/$FILE_SIZE", response.header("Content-Range"))
            val body = response.body?.bytes() ?: ByteArray(0)
            assertEquals(65536, body.size)
            assertBytesEqual(body, start)
        }
    }

    // --- 上游坏行为 -------------------------------------------------------------

    /** 上游 206 但不带 `Content-Range`：仍必须把完整、正确的字节交给播放器。 */
    @Test
    fun `serves the file when the upstream omits content-range`() {
        start(sendContentRange = false)
        val read = readSequentially(from = 0L, until = FILE_SIZE.toLong())
        assertEquals(FILE_SIZE.toLong(), read)
    }

    /** 上游忽略 `Range` 直接回 200：代理必须自己对齐到请求偏移，绝不能把整个文件倒给播放器。 */
    @Test
    fun `aligns to the requested offset when the upstream ignores range`() {
        start(ignoreRange = true)
        val target = 3L * 1024 * 1024
        playerCall(target, null).use { response ->
            assertEquals("expected 206", 206, response.code)
            val body = response.body?.bytes() ?: ByteArray(0)
            assertTrue("empty body", body.isNotEmpty())
            assertBytesEqual(body, target)
        }
    }

    /** `HEAD` 必须给出真实总大小。 */
    @Test
    fun `answers HEAD with the real total size`() {
        start()
        player.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${proxy!!.port}$mediaPath")
                .head()
                .build(),
        ).execute().use { response ->
            assertEquals("unexpected status", 200, response.code)
            assertEquals("wrong total size", FILE_SIZE.toString(), response.header("Content-Length"))
        }
    }

    /** 上游错误码要原样透传（403/404 往往就是直链过期），不能被掩盖成 502。 */
    @Test
    fun `passes an upstream error status through instead of masking it`() {
        start(errorCode = 403)
        playerCall(0, null).use { response ->
            assertEquals("the real upstream reason must survive", 403, response.code)
        }
    }

    /**
     * 极端情况：上游每次只给 5 MB 就掉线（实测行为），窗口仍必须把整个文件补齐。
     */
    @Test
    fun `delivers everything even when the upstream always cuts early`() {
        start(safeRangeBytes = 1)
        val read = readSequentially(from = 0L, until = FILE_SIZE.toLong())
        assertEquals("short reads must be resumed, never treated as EOF", FILE_SIZE.toLong(), read)
    }

    // --- 播放器侧 / 装配 ---------------------------------------------------------

    private lateinit var mediaPath: String

    /** 模拟播放器：反复发 `Range: bytes=<pos>-`，把收到的字节与源逐一比对。 */
    private fun readSequentially(from: Long, until: Long): Long {
        var position = from
        var guard = 0
        while (position < until) {
            check(guard++ < MAX_PLAYER_REQUESTS) { "too many requests, stuck at $position" }
            playerCall(position, null).use { response ->
                assertEquals("unexpected status at $position: ${response.code}", 206, response.code)
                val body = response.body?.bytes() ?: ByteArray(0)
                assertTrue("empty body at $position (status=${response.code})", body.isNotEmpty())
                assertEquals(
                    "wrong Content-Range start at $position",
                    position.toString(),
                    response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-'),
                )
                assertBytesEqual(body, position)
                position += body.size
            }
        }
        return position
    }

    private fun assertBytesEqual(body: ByteArray, absoluteStart: Long) {
        for (i in body.indices) {
            val absolute = absoluteStart + i
            if (absolute >= FILE_SIZE) break
            if (body[i] != source[absolute.toInt()]) {
                throw AssertionError(
                    "byte mismatch at $absolute: got ${body[i]}, want ${source[absolute.toInt()]}",
                )
            }
        }
    }

    private fun playerCall(position: Long, end: Long?) = player.newCall(
        Request.Builder()
            .url("http://127.0.0.1:${proxy!!.port}$mediaPath")
            .header("Range", "bytes=$position-" + (end?.toString() ?: ""))
            .build(),
    ).execute()

    private fun start(
        safeRangeBytes: Int = FakeCdnServer.DEFAULT_SAFE_RANGE,
        truncateLimit: Int = FakeCdnServer.DEFAULT_TRUNCATE_LIMIT,
        sendContentRange: Boolean = true,
        ignoreRange: Boolean = false,
        errorCode: Int? = null,
        windowBytes: Int? = null,
        segmentBytes: Int? = null,
        contentType: String = "video/mp4",
        ttfbMillis: Long = 0,
    ): LocalProxyServer {
        val fake = FakeCdnServer(
            body = source,
            contentType = contentType,
            safeRangeBytes = safeRangeBytes,
            truncateLimit = truncateLimit,
            sendContentRange = sendContentRange,
            ignoreRange = ignoreRange,
            errorCode = errorCode,
            ttfbMillis = ttfbMillis,
        )
        fake.start()
        cdn = fake

        mediaPath = ProxyUpstream.localPathFor("${fake.origin}/media/file.bin")
            ?: error("could not build the local proxy path")

        val server = if (windowBytes == null && segmentBytes == null) {
            LocalProxyServer(userAgent = USER_AGENT)
        } else {
            LocalProxyServer(
                userAgent = USER_AGENT,
                windowBytes = windowBytes ?: DEFAULT_WINDOW_BYTES,
                segmentBytes = segmentBytes ?: DEFAULT_SEGMENT_BYTES,
            )
        }
        server.start()
        proxy = server
        return server
    }

    private companion object {
        const val FILE_SIZE = 40 * 1024 * 1024
        const val DEFAULT_WINDOW_BYTES = 24 * 1024 * 1024
        const val DEFAULT_SEGMENT_BYTES = 8 * 1024 * 1024
        const val SAFE_RANGE_BYTES = 10L * 1024 * 1024
        const val USER_AGENT = "tvmedia-test"
        const val SMALL_READ_BYTES = 64 * 1024
        const val MAX_PLAYER_REQUESTS = 4096
        const val MAX_UPSTREAM_REQUESTS = 64
    }
}
