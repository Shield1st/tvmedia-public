package com.tvmedia.openlist.proxy

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一个**固定大小的内存预读窗口**，覆盖 `[start, start + capacity)` 这段文件内容。
 *
 * 它只负责"存 + 等"，不负责发请求 —— 抓取逻辑在 [LocalProxyServer]，这样窗口本身是纯 JVM、
 * 可以脱网单元测试。
 *
 * ### 为什么需要它（实测依据）
 *
 * 夸克 CDN 实测（模拟器 + app 自己的凭证，见任务目录 `research/upstream-measurements.md`）：
 *
 * 1. **无界请求（open-ended `bytes=N-` / 无 `Range`）被限速到 ~0.10 MiB/s**，
 *    而播放器默认发的就是 open-ended；
 * 2. **有界请求很快**（8 ~ 128 MiB 都 7.5 ~ 12.7 MiB/s），**3 路并发 × 8 MiB = 32 MiB/s**；
 * 3. 每次请求都有固定 TTFB（~0.2 s），所以小块读的吞吐会被请求次数拖垮。
 *
 * 所以必须由本层发出**大块有界请求**（8 MiB × 3 路并发），
 * 把"每个小块付一次 TTFB"变成"每 8 MiB 付一次"，再让播放器从小块读内存。
 *
 * ### 分段语义
 *
 * 窗口按 [segmentBytes] 切成若干分段，每个分段 = 一次上游请求的粒度。
 * 分段必须**从前往后连续填满**（断点续拉也写在同一分段里），这样 `filled[index]` 恒等于
 * 该分段**已连续就绪的前缀长度**，`availableRun` 就只需要在分段边界上停一下。
 */
internal class PrefetchBuffer(
    val start: Long,
    val capacity: Int,
    private val segmentBytes: Int,
) {

    private val lock = ReentrantLock()
    private val dataReady = lock.newCondition()

    private val segmentCount = (capacity + segmentBytes - 1) / segmentBytes
    private val data = ByteArray(capacity)

    /** 每个分段**已连续**写入的字节数。 */
    private val filled = IntArray(segmentCount)

    /** 已经交给某个抓取线程的分段。 */
    private val claimed = BooleanArray(segmentCount)

    /** 不会再写入的分段（已填满，或确定到了文件末尾）。 */
    private val closed = BooleanArray(segmentCount)

    private var generation = 0L
    private var finished = false
    private var failure: Throwable? = null

    val end: Long get() = start + capacity

    fun contains(offset: Long): Boolean = offset >= start && offset < end

    /**
     * 开始一轮新的抓取，作废上一轮（旧线程会通过 generation 检查自行退出）。
     *
     * 新窗口一定是新建的，所以没有"已覆盖"的短路判断 —— 早期版本用
     * 「偏移是否落在 `0..capacity`」代替"窗口是否存在"，导致**首个请求被误判为已覆盖、
     * 抓取永不启动、播放器一直转圈**。
     */
    fun begin(): Long = lock.withLock {
        generation += 1
        for (i in 0 until segmentCount) {
            filled[i] = 0
            claimed[i] = false
            closed[i] = false
        }
        finished = false
        failure = null
        generation
    }

    fun isCurrent(gen: Long): Boolean = lock.withLock { gen == generation }

    /** 领取下一个待抓取的分段；没有则返回 null。 */
    fun claimSegment(gen: Long): Int? = lock.withLock {
        if (gen != generation) return null
        for (i in 0 until segmentCount) {
            if (!claimed[i] && !closed[i]) {
                claimed[i] = true
                return i
            }
        }
        null
    }

    /** 分段 [index] 的绝对起始偏移。 */
    fun segmentOffset(index: Int): Long = start + index.toLong() * segmentBytes

    /** 分段 [index] 应有的长度（最后一个可能短一点）。 */
    fun segmentLength(index: Int): Int = minOf(segmentBytes, capacity - index * segmentBytes)

    /** 分段 [index] **已连续就绪**的字节数（断点续拉的起点就是它）。 */
    fun segmentFilled(gen: Long, index: Int): Int = lock.withLock {
        if (gen != generation) -1 else filled[index]
    }

    /**
     * 把 [length] 字节追加到分段 [index] 的当前末尾。
     *
     * @return 实际写入的字节数；**-1 表示窗口已被 seek 作废**（调用方应立刻停止）。
     */
    fun append(gen: Long, index: Int, buffer: ByteArray, length: Int): Int = lock.withLock {
        if (gen != generation) return -1
        val room = segmentLength(index) - filled[index]
        if (room <= 0) return 0
        val count = minOf(length, room)
        System.arraycopy(buffer, 0, data, index * segmentBytes + filled[index], count)
        filled[index] += count
        dataReady.signalAll()
        count
    }

    /**
     * 标记分段 [index] 不会再写入。
     *
     * @param eof true 表示**确实到了文件末尾**（`Content-Range` 的总大小已到达）——
     *   此后所有分段都关闭，因为后面不会再有数据。
     *   **绝不能因为"这次响应给少了"就传 true**：服务端会谎报长度后掐断，
     *   把掐断当 EOF 会让窗口提前截断、读位置之后出现空洞（`3aeb334`「播 5 秒卡 5 秒」的真因）。
     */
    fun closeSegment(gen: Long, index: Int, eof: Boolean) = lock.withLock {
        if (gen != generation) return
        closed[index] = true
        if (eof) {
            for (i in index + 1 until segmentCount) closed[i] = true
        }
        if (closed.all { it }) finished = true
        dataReady.signalAll()
    }

    /** 抓取线程全部结束（正常或失败）时调用，唤醒所有等待者。 */
    fun finish(gen: Long, error: Throwable? = null) = lock.withLock {
        if (gen != generation) return
        failure = error
        finished = true
        dataReady.signalAll()
    }

    /**
     * 等到 [offset] 处**连续**可读 [wanted] 字节，或窗口结束，或超时。
     *
     * @return 实际连续可读的字节数（可能小于 [wanted]：窗口末尾 / 文件末尾 / 超时）。
     *   调用方必须**只用这个数字**去声明 `Content-Length` —— 对播放器从不说谎。
     */
    fun awaitAtLeast(offset: Long, wanted: Int, timeoutMillis: Long): Int {
        lock.withLock {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                if (!contains(offset)) return 0
                val available = availableRunLocked(offset - start)
                if (available >= wanted) return wanted
                if (finished || failure != null) return available
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return available
                dataReady.await(remaining, TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * 等到 [offset] 处**至少有一个字节**可读，或窗口结束，或超时。
     *
     * 为什么要它：[awaitAtLeast] 会等满整个请求量才返回。发数据时若一上来就等满一整块 8 MiB，
     * 播放器要**等到整块抓好**才能拿到第一个字节 —— 真机实测首字节等了 5.1 秒。
     * 流媒体服务器都是**有了一点就先发**，所以这里给一个"只要不为空就返回"的等待。
     *
     * @return 连续可读字节数（可能远小于 [wanted]），0 表示没有数据。
     */
    fun awaitAny(offset: Long, timeoutMillis: Long): Int {
        lock.withLock {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                if (!contains(offset)) return 0
                val available = availableRunLocked(offset - start)
                if (available > 0) return available
                if (finished || failure != null) return 0
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return 0
                dataReady.await(remaining, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** 复制 [offset] 处最多 [length] 字节。调用方应先 [awaitAtLeast] 确保有数据。 */
    fun read(offset: Long, length: Int): ByteArray {
        lock.withLock {
            if (!contains(offset)) return ByteArray(0)
            val relative = (offset - start).toInt()
            val available = availableRunLocked(relative.toLong())
            val count = minOf(length, available)
            if (count <= 0) return ByteArray(0)
            return data.copyOfRange(relative, relative + count)
        }
    }

    fun failureOrNull(): Throwable? = lock.withLock { failure }

    /**
     * 作废当前抓取（窗口被丢弃 / 切文件时用）。
     *
     * 递增 generation 会让还在跑的抓取线程在下一次 `append` 时立刻退出 —— 否则它们会
     * 把一个已经没人要的窗口拉完，白耗带宽。
     */
    fun invalidate() = lock.withLock {
        generation += 1
        finished = true
        dataReady.signalAll()
    }

    /**
     * 从相对位置 [r] 起**连续**可读的字节数。
     *
     * 分段是并行抓取的，所以必须**跨分段累加**；但只要遇到一个还没就绪的位置就停下 —— 
     * 越过空洞读出去会让播放器拿到错位的数据。
     */
    private fun availableRunLocked(r: Long): Int {
        if (r < 0 || r >= capacity) return 0
        var position = r
        var count = 0
        while (position < capacity) {
            val segment = (position / segmentBytes).toInt()
            val segmentStart = segment.toLong() * segmentBytes
            val segmentFilledEnd = segmentStart + filled[segment]
            if (position >= segmentFilledEnd) break
            val segmentEnd = minOf(segmentStart + segmentBytes, capacity.toLong())
            val next = minOf(segmentEnd, segmentFilledEnd)
            count += (next - position).toInt()
            position = next
        }
        return count
    }
}
