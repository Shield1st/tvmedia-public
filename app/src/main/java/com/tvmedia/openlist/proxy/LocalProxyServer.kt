package com.tvmedia.openlist.proxy

import com.tvmedia.openlist.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 回环 HTTP 代理：**替播放器向上游取数据，并把小块读从内存里供给它**。
 *
 * ### 为什么必须有这一层（全部是实测结论，不是推断）
 *
 * 在模拟器上用 **app 自己的凭证**直连夸克 CDN 实测（1.46 GB MKV，详见任务目录
 * `research/upstream-measurements.md`）：
 *
 * | 事实 | 数值 |
 * |---|---|
 * | 裸 GET（不带 Cookie/Referer/UA） | **`412`** |
 * | **有界**请求（8 ~ 128 MiB） | `206`，长度诚实，**7.5 ~ 12.7 MiB/s** |
 * | **open-ended（`bytes=0-`）/ 无 `Range`** | 声称完整长度，但**只有 0.10 MiB/s** |
 * | 1 GB 有界 | 同样 ~0.10 MiB/s |
 * | 3 路并发 × 8 MiB | **32 MiB/s**（40 Mbps 的片子只需 5 MB/s） |
 *
 * **被限速的是"无界请求"（open-ended / 无 `Range`），不是"大请求"** ——
 * 有界请求到 128 MiB 都很快。而播放器默认发的恰恰是 open-ended，所以这一层必须存在。
 *
 * 所以本代理做三件事：
 *
 * 1. **只向上游发有界 8 MiB 请求**（绝不发 open-ended、绝不发无 `Range` 的请求）；
 * 2. **响应提前结束时从断点续拉**，绝不把"给少了"当成文件末尾
 *    （`3aeb334` 就是把它当 EOF，于是窗口被截断、播放"播 5 秒卡 5 秒"）；
 * 3. **对播放器只说真话，长度按请求算**：open-ended 回**剩余全长**并边收边发 ——
 *    只回一块会骗过"不读 `Content-Range`"的播放器（它以为整个文件只有一块大，播几秒就退出）。
 *
 * ### 窗口
 *
 * 文件按 [segmentBytes] 切成段，一次预读 [windowBytes] 覆盖若干段（**3 路并发**），
 * 并**预抓下一个窗口**（双缓冲）让上游管道不空转。窗口淘汰**只淘汰读位置之前的**，
 * 绝不淘汰正在被读的窗口（`7bcfda1` 的乒乓重抓就是这么来的）。
 *
 * Upstream 解析在 [ProxyUpstream]：CDN host 编码在本地路径里，无法从 path 反推。
 */
internal class LocalProxyServer(
    private val userAgent: String,
    /**
     * 数据源要求的额外上游请求头（夸克 CDN 需要 Cookie + Referer + UA）。
     * 传闭包而不是快照：cookie 会被续期，每次请求都要取最新的。
     */
    private val extraUpstreamHeaders: () -> Map<String, String> = { emptyMap() },
    /** 预读窗口大小。默认 24 MiB = 3 个 8 MiB 分段，正好一次并发抓完。 */
    private val windowBytes: Int = DEFAULT_WINDOW_BYTES,
    /** 上游单次请求的字节数。**实测必须 ≤ 10 MiB**，取 8 MiB。 */
    private val segmentBytes: Int = SEGMENT_BYTES,
) {

    init {
        require(segmentBytes in 1..SEGMENT_MAX_BYTES) { "segmentBytes must be <= the measured 10MiB limit" }
        require(windowBytes >= segmentBytes) { "windowBytes must cover at least one segment" }
    }

    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val workers = Executors.newCachedThreadPool()
    private val backend = OkHttpClient.Builder()
        .connectTimeout(BACKEND_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(BACKEND_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 哪些 path 的上游**忽略了我们发的 `Range`**（对我们的有界请求回 `200`）。
     *
     * 这类上游**没法从任意偏移续拉**（每次响应都从头开始，且在某个偏移处被掉线），
     * 所以不能按请求长度声明 `Content-Length` —— 否则会声明一个写不出来的长度而断流。
     * 这种情况只交一块，让播放器自己接着问。
     */
    private val upstreamIgnoresRange = ConcurrentHashMap.newKeySet<String>()

    /** 上游报过的 `Content-Type`（项目里多是 MKV，**不能写死 `video/mp4`**）。 */
    private val contentTypes = ConcurrentHashMap<String, String>()

    /** 上游报过的文件总大小（来自 `Content-Range`），用来给播放器正确的 `Content-Range`。 */
    private val totalSizes = ConcurrentHashMap<String, Long>()

    /** 上游拒绝时的真实状态码（403/404 往往就是直链过期），拿不到数据时回给播放器以便定位。 */
    private val upstreamStatus = ConcurrentHashMap<String, Int>()

    private val windows = ArrayList<Window>(MAX_WINDOWS)
    private var windowsPath: String? = null

    /** 播放器最近一次读到的偏移，决定预抓方向，也决定哪些窗口**不能**被淘汰。 */
    @Volatile
    private var lastReadOffset = 0L

    private inner class Window(val buffer: PrefetchBuffer) {
        @Volatile
        var generation: Long = 0

        @Volatile
        var started = false

        @Volatile
        var fetchDone = false

        val start: Long get() = buffer.start
        val end: Long get() = buffer.end

        fun contains(offset: Long): Boolean = buffer.contains(offset)
    }

    val port: Int get() = serverSocket.localPort

    fun start() {
        AppLog.i(
            TAG,
            "proxy mode=read-ahead window=${windowBytes / MIB}MiB " +
                "segment=${segmentBytes / MIB}MiB concurrency=$CONCURRENCY maxWindows=$MAX_WINDOWS",
        )
        Thread({ acceptLoop() }, "local-proxy-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { serverSocket.close() }
        workers.shutdownNow()
        synchronized(this) {
            windows.forEach { it.buffer.invalidate() }
            windows.clear()
            windowsPath = null
        }
        totalSizes.clear()
        contentTypes.clear()
        upstreamStatus.clear()
        upstreamIgnoresRange.clear()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                return // closed on purpose
            }
            workers.execute { handleSafely(socket) }
        }
    }

    private fun handleSafely(socket: Socket) {
        try {
            socket.use { handle(it) }
        } catch (e: SocketTimeoutException) {
            AppLog.d(TAG, "idle connection closed")
        } catch (e: IOException) {
            // A player closing early is normal (seek, stop, buffer full).
            AppLog.d(TAG, "connection ended: ${e.message}")
        } catch (e: Exception) {
            AppLog.w(TAG, "request failed", e)
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        socket.soTimeout = IDLE_TIMEOUT_MILLIS

        // Keep-alive matters here: a player issues many Range requests over one connection.
        while (true) {
            val request = readRequest(input) ?: return
            if (request.method != "GET" && request.method != "HEAD") {
                writeStatus(output, 405, "Method Not Allowed")
                return
            }
            val path = request.target.substringBefore('?')
            val query = request.target.substringAfter('?', "")
            if (!ProxyUpstream.isForwardable(path)) {
                AppLog.w(TAG, "rejected unsupported path: $path")
                writeStatus(output, 404, "Not Found")
                return
            }
            val served = serve(
                output = output,
                headOnly = request.method == "HEAD",
                path = path,
                query = query,
                range = parseRange(request.headers["range"]),
                keepAlive = request.wantsKeepAlive,
            )
            if (!served || !request.wantsKeepAlive) return
        }
    }

    // --- 服务播放器 -------------------------------------------------------------

    private fun serve(
        output: BufferedOutputStream,
        headOnly: Boolean,
        path: String,
        query: String,
        range: RequestedRange?,
        keepAlive: Boolean,
    ): Boolean {
        if (ProxyUpstream.resolveUpstream(path, query) == null) {
            AppLog.w(TAG, "unsupported proxy path: $path")
            writeStatus(output, 404, "Not Found")
            return false
        }

        val start0 = range?.start ?: 0L
        if (headOnly) return serveHead(output, path, query, start0, keepAlive)

        // **suffix range（`bytes=-N`）要的是文件末尾 N 字节**，不是开头。
        // MKV 的索引（Cues / SeekHead）在文件末尾，外部播放器探测末尾是常规操作；
        // 当成"从头开始"会给它错的数据，表现就是"未找到文件"。
        val resolved = if (range?.suffixLength != null) {
            resolveSuffix(range, path, query) ?: run {
                AppLog.w(TAG, "could not resolve the suffix range for $path")
                writeStatus(output, 416, "Range Not Satisfiable")
                return false
            }
        } else {
            range
        }

        return if (resolved == null) {
            // 播放器没要 Range → 它要的是**整个文件**。按 `Content-Length: 总大小` 流式发完，
            // 不能像 206 那样只给一块（`200` 的 Content-Length 必须是完整实体长度）。
            streamWholeFile(output, path, query)
        } else {
            serveRange(output, path, query, resolved, keepAlive)
        }
    }

    /**
     * 有 `Range`：**边收边发**，`Content-Length` 用**真实长度**。
     *
     * ### 为什么要这样（两条都是真机实测的教训）
     *
     * 1. **首字节要快**：早期实现先抓好一整块 8 MiB 再回，真机日志里首字节等了 **5.1 秒**。
     *    播放器每次重连都要先卡这么久，表现为起播慢、周期性卡顿。
     * 2. **长度必须是真的**：`bytes=N-`（open-ended，播放器的默认形式）的
     *    `Content-Length` 必须是**剩余全长**。早期实现只报一块 8 MiB，
     *    **不看 `Content-Range` 的播放器**会以为整个文件只有 8 MiB，播完就"到末尾"退出
     *    （真机症状：播 4 秒左右就退出）。
     *
     * 真正的流媒体服务器（OpenList 的 `/d`、nginx）就是这么做的：长度按请求算，数据边收边发。
     * 播放器会在流结束后自己重发请求，所以**不需要**我们提前把整段抓好。
     *
     * **总大小未知时退化为"只交一块"**：宁可让播放器多问几次，
     * 也绝不能声明一个我们写不出来的 `Content-Length`（那会直接断流）。
     */
    private fun serveRange(
        output: BufferedOutputStream,
        path: String,
        query: String,
        range: RequestedRange,
        keepAlive: Boolean,
    ): Boolean {
        val start = range.start
        val total = totalSizes[path] ?: probeTotal(path, query, start)

        // 总大小未知、或上游不守 Range → 只交一块（安全，绝不声明写不出来的长度）。
        // 已知总大小且上游守 Range → 按请求长度流式发完（播放器不必反复重连）。
        if (total == null || upstreamIgnoresRange.contains(path)) {
            return serveOneChunk(output, path, query, range, keepAlive)
        }

        val requested = when {
            range.end != null -> range.end - start + 1
            else -> total - start
        }
        if (requested <= 0 || start >= total) {
            writeStatus(output, 416, "Range Not Satisfiable")
            return false
        }
        val contentLength = minOf(requested, total - start)
        val end = start + contentLength - 1

        // 先把第一块数据等到手（这样才能确定 Content-Type，也才能在拿不到数据时给出真实错误）。
        var chunk = pull(path, query, start, minOf(contentLength, SERVE_CHUNK_BYTES.toLong()).toInt())
        if (chunk.isEmpty()) {
            val status = upstreamStatus[path] ?: 502
            AppLog.w(TAG, "no data for $path @$start (status=$status)")
            writeStatus(output, status, "Upstream Unavailable")
            return false
        }

        AppLog.d(TAG, "serve 206 $path @$start-$end/$total (first=${chunk.size})")
        writeHead(
            output = output,
            code = HTTP_PARTIAL,
            contentType = contentTypes[path] ?: DEFAULT_CONTENT_TYPE,
            contentLength = contentLength,
            contentRange = "bytes $start-$end/$total",
            keepAlive = keepAlive,
        )

        // 边收边发：第一块已经在手上，剩下的来多少发多少。
        var position = start
        var remaining = contentLength
        while (remaining > 0) {
            val take = minOf(chunk.size.toLong(), remaining).toInt()
            output.write(chunk, 0, take)
            output.flush()
            position += take
            remaining -= take
            if (remaining <= 0) break
            chunk = pull(path, query, position, minOf(remaining, SERVE_CHUNK_BYTES.toLong()).toInt())
            if (chunk.isEmpty()) break
        }
        if (remaining != 0L) {
            // 声明了 Content-Length 却没写够 —— 只能断连（绝不能复用连接让播放器错位）。
            AppLog.w(TAG, "short write for $path @$start: $remaining bytes missing")
            return false
        }
        return true
    }

    /**
     * 总大小未知时的保守路径：交一块，并把**这一块的实际长度**声明为 `Content-Length`。
     *
     * 这样绝不可能声明了写不出来的长度。播放器随后会按它拿到的字节数继续请求。
     */
    private fun serveOneChunk(
        output: BufferedOutputStream,
        path: String,
        query: String,
        range: RequestedRange,
        keepAlive: Boolean,
    ): Boolean {
        val start = range.start
        val wanted = range.end?.let {
            minOf(it - start + 1, SERVE_CHUNK_BYTES.toLong()).coerceAtLeast(1L).toInt()
        } ?: SERVE_CHUNK_BYTES

        val payload = pull(path, query, start, wanted)
        if (payload.isEmpty()) {
            val status = upstreamStatus[path] ?: 502
            AppLog.w(TAG, "no data for $path @$start (status=$status, total unknown)")
            writeStatus(output, status, "Upstream Unavailable")
            return false
        }
        AppLog.d(TAG, "serve 206 $path @$start..${start + payload.size - 1}/* (one chunk)")
        writeHead(
            output = output,
            code = HTTP_PARTIAL,
            contentType = contentTypes[path] ?: DEFAULT_CONTENT_TYPE,
            contentLength = payload.size.toLong(),
            contentRange = "bytes $start-${start + payload.size - 1}/*",
            keepAlive = keepAlive,
        )
        output.write(payload)
        output.flush()
        return true
    }

    /**
     * 无 `Range`：流式发完整个文件。
     *
     * `Content-Length` 必须是**完整长度**（200 的语义），所以边取边写；
     * 中途取不到数据就只能关连接（让播放器看到"不完整"而不是"完整但短"）。
     */
    private fun streamWholeFile(output: BufferedOutputStream, path: String, query: String): Boolean {
        val total = totalSizes[path] ?: probeTotal(path, query, 0L)
        if (total == null) {
            AppLog.w(TAG, "no total size for $path; cannot stream it whole")
            writeStatus(output, 502, "Upstream Unavailable")
            return false
        }
        AppLog.d(TAG, "serve 200 $path (whole file, $total bytes)")
        writeHead(
            output = output,
            code = HTTP_OK,
            contentType = contentTypes[path] ?: DEFAULT_CONTENT_TYPE,
            contentLength = total,
            contentRange = null,
            keepAlive = false,
        )
        var position = 0L
        while (position < total) {
            val chunk = pull(path, query, position, SERVE_CHUNK_BYTES)
            if (chunk.isEmpty()) {
                AppLog.w(TAG, "upstream stopped at $position of $total for $path")
                return false
            }
            output.write(chunk)
            output.flush()
            position += chunk.size
        }
        // 整个文件已经发完，这条连接没有复用的意义。
        return false
    }

    /**
     * 从窗口里取 [position] 处最多 [wanted] 字节，必要时创建 / 推进窗口。
     *
     * 这是**唯一**读取窗口的入口，Range 路径与整文件流式路径共用它，
     * 避免两处各写一遍窗口管理（那是出过 bug 的地方）。
     */
    private fun pull(path: String, query: String, position: Long, wanted: Int): ByteArray {
        // 先声明"正在读这里"，再取窗口 —— 窗口淘汰就是靠它避开正在被读的那一个。
        lastReadOffset = position
        val window = windowFor(path, position)
        startFetching(window, path, query)

        // **首字节优先**：先等"有数据"，而不是等满 wanted。
        // 一上来就等满一整块会让播放器等到整块抓好才见到第一个字节（真机 5.1 秒）。
        // 想多给一点时由调用方自己决定要不要再等（见 [awaitAtLeast]）。
        var available = window.buffer.awaitAny(position, READ_WAIT_MILLIS)
        if (available <= 0 && window.buffer.failureOrNull() == null) {
            // 等待期间窗口可能被 seek 换掉了：重新定位一次再等。
            available = windowFor(path, position).buffer.awaitAny(position, READ_WAIT_MILLIS)
        }
        if (available <= 0) return ByteArray(0)

        val chunk = window.buffer.read(position, available)
        if (chunk.isNotEmpty()) {
            lastReadOffset = position + chunk.size
            prefetchAhead(path, query)
        }
        return chunk
    }

    /**
     * 把 `bytes=-N`（末尾 N 字节）解析成一个具体的 `[start, end]`。
     *
     * 需要先知道总大小，所以拿不到时先探一次（一次 1 字节的有界请求）。
     * 返回 null 表示无法满足（应当回 416）。
     */
    private fun resolveSuffix(range: RequestedRange, path: String, query: String): RequestedRange? {
        val suffix = range.suffixLength ?: return range
        val total = totalSizes[path] ?: probeTotal(path, query, 0L) ?: return null
        if (total <= 0) return null
        val length = minOf(suffix, total)
        return RequestedRange(total - length, total - 1)
    }

    /** `HEAD` 只要总大小：用一次 1 字节的有界请求去读上游响应头。 */
    private fun serveHead(
        output: BufferedOutputStream,
        path: String,
        query: String,
        start: Long,
        keepAlive: Boolean,
    ): Boolean {
        val total = totalSizes[path] ?: probeTotal(path, query, start)
        if (total == null) {
            AppLog.w(TAG, "HEAD for $path but the upstream reported no size")
            writeStatus(output, 502, "Upstream Unavailable")
            return false
        }
        writeHead(
            output,
            HTTP_OK,
            contentTypes[path] ?: DEFAULT_CONTENT_TYPE,
            total,
            null,
            keepAlive,
        )
        return true
    }

    /**
     * `HEAD` 探测总大小（只有这里需要"不要数据也要大小"）。
     *
     * 用一次 1 字节的有界请求读上游响应头：
     * - 上游回 `200`（忽略 Range）→ `Content-Length` 就是整个文件大小；
     * - 上游回 `206` → 取 `Content-Range` 的总大小；
     * - 回 `206` 却不带 `Content-Range` → **推不出来**，返回 null（调用方回 502，绝不编造）。
     *
     * 注意：**正式取数据时不要依赖它** —— 总大小由真正的抓取（`requestRange`）确定，
     * 那时一定带着真实响应头，比另外探测可靠。
     */
    private fun probeTotal(path: String, query: String, offset: Long): Long? {
        val url = ProxyUpstream.resolveUpstream(path, query) ?: return null
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            // 1 字节的有界请求：既不触发上限，也不用读 body。
            .header("Range", "bytes=$offset-${offset}")
        extraUpstreamHeaders().forEach { (name, value) -> builder.header(name, value) }
        builder.header("Accept-Encoding", "identity")
        return runCatching {
            backend.newCall(builder.build()).execute().use { response ->
                response.header("Content-Type")?.let { contentTypes[path] = it }
                if (response.code == HTTP_OK) {
                    // 对我们的有界请求回 200 = 上游忽略 Range。
                    // 这类上游没法从任意偏移续拉（每次响应都从头开始、且会在某处掉线），
                    // 所以后面**只能交一块**，绝不能按请求长度声明 Content-Length。
                    upstreamIgnoresRange.add(path)
                    // 忽略 Range、回整个文件：`Content-Length` 就是文件大小。
                    response.body?.contentLength()?.takeIf { it >= 0 }
                } else {
                    response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                }
            }
        }.getOrNull()?.also { totalSizes[path] = it }
    }

    // --- 窗口管理 ---------------------------------------------------------------

    private fun windowFor(path: String, offset: Long): Window = synchronized(this) {
        if (windowsPath != path) {
            windows.forEach { it.buffer.invalidate() }
            windows.clear()
            windowsPath = path
            lastReadOffset = offset
            totalSizes.remove(path)
            contentTypes.remove(path)
            upstreamStatus.remove(path)
            upstreamIgnoresRange.remove(path)
        }
        windows.firstOrNull { it.contains(offset) }?.let { return it }

        // 腾地方：**只淘汰不包含当前读位置的窗口**（在用的窗口永远不动）。
        while (windows.size >= MAX_WINDOWS) {
            val victim = windows
                .filter { !it.contains(lastReadOffset) }
                .minByOrNull { distanceTo(it, lastReadOffset) }
                ?: break
            AppLog.d(TAG, "dropping window @${victim.start} (read position moved to $lastReadOffset)")
            victim.buffer.invalidate()
            windows.remove(victim)
        }

        // 窗口按分段对齐：seek 到任意位置都只多抓一个分段，不会从头重来。
        val aligned = Math.floorDiv(offset, segmentBytes.toLong()) * segmentBytes
        val created = Window(PrefetchBuffer(aligned, windowBytes, segmentBytes))
        windows.add(created)
        AppLog.d(TAG, "new window @${created.start}..${created.end - 1} for read @$offset")
        created
    }

    private fun distanceTo(window: Window, offset: Long): Long = when {
        offset < window.start -> window.start - offset
        offset >= window.end -> offset - window.end
        else -> 0L
    }

    /** 每个窗口只启动一次抓取。 */
    private fun startFetching(window: Window, path: String, query: String) {
        val generation = synchronized(window) {
            if (window.started) return
            window.started = true
            window.generation = window.buffer.begin()
            window.generation
        }
        workers.execute { fetchWindow(window, path, query, generation) }
    }

    /**
     * 预抓**下一个**窗口（双缓冲），让上游管道不空转。
     *
     * 只在播放器**确实还在当前窗口里**时才往前抓一个 —— 否则会一路把整个文件抓下来。
     */
    private fun prefetchAhead(path: String, query: String) {
        val current = synchronized(this) {
            windows.firstOrNull { it.contains(lastReadOffset) } ?: return
        }
        if (!current.fetchDone) return
        val next = windowFor(path, current.end)
        if (next.start != current.end) return
        startFetching(next, path, query)
    }

    // --- 抓取 -------------------------------------------------------------------

    /**
     * 用 [CONCURRENCY] 路并发把窗口填满：每路领一个分段，发**有界**请求，被掐断就续拉。
     */
    private fun fetchWindow(window: Window, path: String, query: String, generation: Long) {
        val failure = AtomicReference<Throwable?>(null)
        val eof = AtomicBoolean(false)
        val latch = CountDownLatch(CONCURRENCY)

        repeat(CONCURRENCY) {
            workers.execute {
                try {
                    while (failure.get() == null && !eof.get()) {
                        val index = window.buffer.claimSegment(generation) ?: break
                        fetchSegment(window, path, query, generation, index, eof, failure)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }

        // 兜底：任何一路出意外都不能让等待方永久阻塞（真机症状就是"无限加载中"）。
        latch.await(WINDOW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        window.buffer.finish(generation, failure.get())
        window.fetchDone = true
        failure.get()?.let { AppLog.w(TAG, "prefetch stopped (window @${window.start}): ${it.message}") }
        if (!eof.get()) prefetchAhead(path, query)
    }

    /**
     * 抓满一个分段。
     *
     * **关键：这是个循环。** 上游单次响应会被掐断（谎报长度后只给 ~5 MB），
     * 所以一次请求拿不满一个分段时必须**从断点继续拉**，绝不能把"给少了"当成文件末尾 ——
     * 那会在读位置之后留下空洞，播放器只能干等（`3aeb334` 的「播 5 秒卡 5 秒」）。
     */
    private fun fetchSegment(
        window: Window,
        path: String,
        query: String,
        generation: Long,
        index: Int,
        eof: AtomicBoolean,
        failure: AtomicReference<Throwable?>,
    ) {
        val buffer = window.buffer
        val length = buffer.segmentLength(index)

        while (true) {
            val filled = buffer.segmentFilled(generation, index)
            if (filled < 0) return // 窗口被 seek 作废
            if (filled >= length) {
                buffer.closeSegment(generation, index, eof = false)
                return
            }
            val offset = buffer.segmentOffset(index) + filled
            // 已经从 Content-Range 得知总大小，就不用再问文件末尾之后的那一段。
            val knownTotal = totalSizes[path]
            if (knownTotal != null && offset >= knownTotal) {
                buffer.closeSegment(generation, index, eof = true)
                eof.set(true)
                return
            }
            val want = minOf(segmentBytes.toLong(), length - filled.toLong()).toInt()

            var attempt = 0
            var outcome: RangeOutcome? = null
            while (attempt < SEGMENT_MAX_ATTEMPTS) {
                attempt++
                val result = requestRange(path, query, generation, index, offset, want, buffer)
                if (result.superseded) return
                if (result.error == null) {
                    outcome = result
                    break
                }
                AppLog.w(TAG, "upstream $path @$offset+$want attempt $attempt failed: ${result.error.message}")
                if (attempt < SEGMENT_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(SEGMENT_RETRY_BACKOFF_MS * attempt)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }

            if (outcome == null) {
                failure.compareAndSet(null, IOException("segment @$offset failed after $SEGMENT_MAX_ATTEMPTS attempts"))
                return
            }
            if (outcome.eof) {
                buffer.closeSegment(generation, index, eof = true)
                eof.set(true)
                return
            }
            if (outcome.written <= 0) {
                // 上面已经把"一个字节都没给且未到 EOF"当成错误走重试了；
                // 能走到这里说明重试也没用 —— 关掉这个分段，让等待方尽快拿到结果（不阻塞）。
                AppLog.w(TAG, "upstream gave nothing for $path @$offset; closing the segment")
                buffer.closeSegment(generation, index, eof = false)
                return
            }
            // 拿到了一部分 → 继续循环，从断点续拉剩下的。
        }
    }

    private class RangeOutcome(
        val written: Int,
        /** 上游 `Content-Range` 里的真实总大小；拿不到为 null。 */
        val total: Long?,
        /** 上游明确表示文件到此结束（总大小已到达）。 */
        val eof: Boolean,
        /** 窗口被 seek 作废，调用方应立刻停止。 */
        val superseded: Boolean,
        val error: Throwable?,
    )

    /**
     * 一次**有界**上游请求。
     *
     * 只接受 `200`（上游忽略 Range，需要自己对齐）与 `206`。
     * 其余状态码原样记录并报错（403/404 往往就是直链过期，保留真实原因便于排查）。
     */
    private fun requestRange(
        path: String,
        query: String,
        generation: Long,
        index: Int,
        offset: Long,
        want: Int,
        buffer: PrefetchBuffer,
    ): RangeOutcome {
        val url = ProxyUpstream.resolveUpstream(path, query)
            ?: return RangeOutcome(0, null, false, false, IOException("unsupported proxy path: $path"))

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            // **必须是有界 Range**：open-ended / 无 Range 会落进 CDN 的限速区（实测 0.10 MiB/s）。
            .header("Range", "bytes=$offset-${offset + want - 1}")
        extraUpstreamHeaders().forEach { (name, value) -> builder.header(name, value) }
        // 禁止 OkHttp 透明解压：一旦上游回 gzip，body 长度会变、Range 语义会被破坏。
        builder.header("Accept-Encoding", "identity")

        backend.newCall(builder.build()).execute().use { response ->
            if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                // 请求超出文件末尾 → 这就是货真价实的 EOF。
                return RangeOutcome(0, null, true, false, null)
            }
            if (response.code != HTTP_OK && response.code != HTTP_PARTIAL) {
                val snippet = runCatching { response.body?.string()?.take(ERROR_BODY_SNIPPET) }
                    .getOrNull().orEmpty()
                // 上游错误页可能回显带签名的直链 —— 凭证不进日志。
                val safeSnippet = if (snippet.contains("http") || snippet.contains("?")) {
                    "<redacted: looks like it contains a URL>"
                } else {
                    snippet
                }
                AppLog.w(
                    TAG,
                    "upstream HTTP ${response.code} for $path @$offset+$want " +
                        "(request headers=${extraUpstreamHeaders().keys}) body=$safeSnippet",
                )
                // 记住真实状态码：拿不到数据时可以把它回给播放器，保留「403 = 直链过期」这个关键线索。
                upstreamStatus[path] = response.code
                return RangeOutcome(0, null, false, false, IOException("upstream HTTP ${response.code}"))
            }

            // 上游回 200（忽略 Range）：`Content-Length` **就是整个文件大小**，不要再加 offset。
            // 回 206 才从 `Content-Range` 取总大小（拿不到就是 null，由调用方保守处理）。
            val total = if (response.code == HTTP_OK) {
                response.body?.contentLength()?.takeIf { it >= 0 }
            } else {
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            }
            total?.let { totalSizes[path] = it }
            response.header("Content-Type")?.let { contentTypes[path] = it }
            upstreamStatus.remove(path)

            val body = response.body
                ?: return RangeOutcome(0, total, false, false, IOException("upstream returned no body"))
            val stream = body.byteStream()

            // 对我们的有界请求回 200 = 上游忽略 Range。记下来：后面不能按请求长度声明长度。
            if (response.code == HTTP_OK) {
                upstreamIgnoresRange.add(path)
            }
            // 上游忽略了 Range、直接回整个文件：必须自己跳到请求的偏移。
            if (response.code == HTTP_OK && offset > 0) {
                if (!skipFully(stream, offset)) {
                    return RangeOutcome(0, total, false, false, IOException("could not skip to $offset"))
                }
            }

            var written = 0
            val chunk = ByteArray(STREAM_CHUNK_BYTES)
            // 有些上游（OpenList 的 `/d` 那类）会**谎报 `Content-Length` 然后直接掉线**
            // （实测：请求 8 MiB 只给 ~5 MB）。夸克 CDN 不会这么做，但"给少了"绝不能当 EOF ——
            // 对 OkHttp 来说这是**异常**（unexpected end of stream），不是短读，
            // 所以这里必须把它翻译成一次正常的响应结束，交给外层从断点续拉。
            // **边界**：一个字都没拿到就断 → 照原样抛，绝不伪装成 EOF（那是静默截断）。
            try {
                while (written < want) {
                    val read = stream.read(chunk, 0, minOf(chunk.size.toLong(), (want - written).toLong()).toInt())
                    if (read <= 0) break
                    val appended = buffer.append(generation, index, chunk, read)
                    if (appended < 0) return RangeOutcome(written, total, false, true, null)
                    if (appended == 0) break
                    written += appended
                }
            } catch (e: IOException) {
                if (written == 0) {
                    return RangeOutcome(
                        written = 0,
                        total = total,
                        eof = false,
                        superseded = false,
                        error = IOException("upstream cut before sending any byte: ${e.message}", e),
                    )
                }
                AppLog.d(
                    TAG,
                    "upstream cut early at $offset+$written of $want; resuming from the breakpoint",
                )
            }

            // **只有总大小确实到达才算 EOF**。没拿到总大小时绝不因为"给少了"就宣布结束 ——
            // 上游会谎报长度后掉线，把掉线当 EOF 会在读位置之后留下空洞（`3aeb334` 的真 bug）。
            val reachedTotal = total != null && offset + written >= total
            // 回了 206 却一个字节不给，而且又没到总大小 —— 这是异常，交给外层重试，
            // 而不是静静地关掉分段（那会在读位置之后留下一个永不填上的空洞）。
            val sentNothing = written == 0 && !reachedTotal
            return RangeOutcome(
                written = written,
                total = total,
                eof = reachedTotal,
                superseded = false,
                error = if (sentNothing) IOException("upstream sent no bytes for $offset+$want") else null,
            )
        }
    }

    // --- 写响应 -----------------------------------------------------------------

    /**
     * 写响应头。只转发**实体头**，绝不把上游的 `Connection` / `Keep-Alive` /
     * `Transfer-Encoding` 这类逐跳头带出来 —— 连接语义由本代理自己决定。
     */
    private fun writeHead(
        output: BufferedOutputStream,
        code: Int,
        contentType: String?,
        contentLength: Long?,
        contentRange: String?,
        keepAlive: Boolean,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: ").append(contentType ?: DEFAULT_CONTENT_TYPE).append("\r\n")
            if (contentRange != null) {
                append("Content-Range: ").append(contentRange).append("\r\n")
            }
            if (contentLength != null) {
                append("Content-Length: ").append(contentLength).append("\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        HTTP_OK -> "OK"
        HTTP_PARTIAL -> "Partial Content"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    private fun writeStatus(output: BufferedOutputStream, code: Int, message: String) {
        val body = "$code $message".toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    /** 丢弃 [count] 个字节（上游忽略 Range 时的对齐）。 */
    private fun skipFully(stream: InputStream, count: Long): Boolean {
        var remaining = count
        val buffer = ByteArray(STREAM_CHUNK_BYTES)
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = stream.read(buffer, 0, want)
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }

    // --- 请求解析 ---------------------------------------------------------------

    /** [parseRange] 返回 null 表示播放器**没有发 `Range`**（这时不能替它造一个）。 */
    private data class RequestedRange(
        val start: Long,
        val end: Long?,
        /** 非 null 表示这是 `bytes=-N`（文件末尾 N 字节），[start]/[end] 尚未解析。 */
        val suffixLength: Long? = null,
    )

    private data class ProxyRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val wantsKeepAlive: Boolean,
    )

    private fun readRequest(input: InputStream): ProxyRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts.getOrElse(2) { HTTP_1_1 }

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val connection = headers["connection"]?.lowercase()
        val keepAlive = when (connection) {
            "close" -> false
            "keep-alive" -> true
            else -> version.equals(HTTP_1_1, ignoreCase = true)
        }
        return ProxyRequest(method, target, headers, keepAlive)
    }

    /**
     * 解析 `Range`。返回 null = 当作没有 Range（没发 / 格式不认识）。
     *
     * `bytes=-N`（末尾 N 字节）必须**真的给末尾** —— 早期版本把它当成"从头开始"，
     * 而外部播放器正是靠读文件末尾的索引来识别 MKV 的，拿错数据就报"未找到文件"。
     */
    private fun parseRange(header: String?): RequestedRange? {
        if (header == null) return null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        if (startText.isEmpty()) {
            // `bytes=-N`：最后 N 字节。
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            return RequestedRange(0L, null, suffixLength = suffix)
        }
        val start = (startText.toLongOrNull() ?: return null).coerceAtLeast(0L)
        val end = endText.toLongOrNull()
        if (end != null && end < start) {
            AppLog.w(TAG, "reversed range '$header' ignored")
            return null
        }
        return RequestedRange(start, end)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(byte.toChar())
            if (builder.length > MAX_LINE_LENGTH) return null
        }
    }

    private companion object {
        private const val TAG = "LocalProxyServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val HTTP_1_1 = "HTTP/1.1"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BACKLOG = 50
        private const val MAX_LINE_LENGTH = 8192
        private const val STREAM_CHUNK_BYTES = 64 * 1024
        private const val WRITE_CHUNK_BYTES = 256 * 1024
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        private const val MIB = 1024 * 1024
        private const val BACKEND_CONNECT_TIMEOUT_SECONDS = 15L
        private const val BACKEND_READ_TIMEOUT_SECONDS = 30L
        private const val IDLE_TIMEOUT_MILLIS = 30_000
        private const val ERROR_BODY_SNIPPET = 200

        /** 预读窗口：3 个分段，正好让 [CONCURRENCY] 路一次并发抓完。 */
        private const val DEFAULT_WINDOW_BYTES = 24 * MIB

        /** 上游单次请求字节数。实测 8 MiB 有界 8.6 MiB/s、3 路并发 32 MiB/s，够用。 */
        private const val SEGMENT_BYTES = 8 * MIB

        /**
         * 编译期守住分片上限：分段越大，首字节与内存占用越差（窗口 = 3 个分段 × 双缓冲），
         * 而实测 8 MiB 已经远超所需（40 Mbps 的片子只要 5 MiB/s）。要调大先读
         * `research/upstream-measurements.md` —— 真正的硬约束是**绝不发无界请求**，不是分片大小。
         */
        private const val SEGMENT_MAX_BYTES = 10 * MIB

        /** 实测：3 路并发 × 8 MiB = 32 MiB/s（端到端经代理读 96 MiB = 16.8 MiB/s）。 */
        private const val CONCURRENCY = 3

        /** 当前窗口 + 预抓的下一个窗口（双缓冲）。峰值内存 ≈ 2 × 24 MiB。 */
        private const val MAX_WINDOWS = 2

        /** 单次交给播放器的上限：够大以减少往返，又不至于长时间占用。 */
        private const val SERVE_CHUNK_BYTES = 8 * MIB

        /** 等预读数据的最长时间；超时就返回已有字节（绝不无限阻塞）。 */
        private const val READ_WAIT_MILLIS = 30_000L

        /** 一个窗口的抓取总时限。 */
        private const val WINDOW_TIMEOUT_SECONDS = 60L

        /** 单个分段的请求最多试几次（单次抖动不该让播放中断）。 */
        private const val SEGMENT_MAX_ATTEMPTS = 4

        private const val SEGMENT_RETRY_BACKOFF_MS = 300L
    }
}
