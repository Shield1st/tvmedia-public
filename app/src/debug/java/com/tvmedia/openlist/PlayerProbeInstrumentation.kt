package com.tvmedia.openlist

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import com.tvmedia.openlist.data.model.Entry
import com.tvmedia.openlist.data.source.MediaSource
import com.tvmedia.openlist.data.source.QuarkSession
import com.tvmedia.openlist.proxy.LocalProxy
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **只用于 debug 构建**的端到端探针（不进正式包）：
 * 让**系统播放器那一栈**（framework `MediaPlayer` = NuPlayer + NuCachedSource2 + HTTP source）
 * 真的通过回环代理播一段真实夸克直链。
 *
 * 为什么需要它：模拟器上的 AOSP 播放器（`com.android.gallery3d/.app.MovieActivity`）
 * **targetSdk=28 且未声明 `usesCleartextTraffic`，因此不允许明文 HTTP** ——
 * 它连 `http://127.0.0.1` 都连不上（实测：`NuCachedSource2: source returned error -1`，
 * 代理侧一条请求都收不到）。那是模拟器的限制，不是本项目的 bug。
 * 所以这里用**本 app 自己**（`usesCleartextTraffic=true`）的进程调 framework `MediaPlayer`，
 * 走的是和电视上外部播放器**同一套** HTTP/解复用栈。
 *
 * 它验证的是「播 4 秒就退出」那条故障的另一半：
 * 播放器**真的**能从代理连续取到数据、能起播、能推进、能 seek。
 *
 * 运行：
 * ```
 * adb shell am instrument -w com.tvmedia.openlist/com.tvmedia.openlist.PlayerProbeInstrumentation
 * adb logcat -d -s PlayerProbe
 * ```
 */
class PlayerProbeInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        Thread {
            try {
                run()
            } catch (t: Throwable) {
                Log.e(TAG, "player probe failed: ${t.message}", t)
            } finally {
                finish(Activity.RESULT_OK, Bundle())
            }
        }.start()
    }

    private fun run() {
        val context = targetContext
        val source = QuarkSession.source(context) ?: error("no quark source (not logged in?)")
        val entry = findPlayable(source) ?: error("no playable entry in the account")
        val upstream = runBlocking { source.resolvePlayUrl(entry.path) }
        val port = LocalProxy.ensureStarted(context, foregroundService = false)
            ?: error("the proxy did not start")
        val local = LocalProxy.toLocalUrl(upstream, port)
        check(local != upstream) { "toLocalUrl did not rewrite the url" }
        Log.i(TAG, "playing ${entry.name} (${entry.size} bytes) through 127.0.0.1:$port")

        val player = MediaPlayer()
        val prepared = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var error = ""

        player.setOnPreparedListener {
            Log.i(TAG, "prepared: duration=${it.duration}ms video=${it.videoWidth}x${it.videoHeight}")
            prepared.countDown()
        }
        player.setOnErrorListener { _, what, extra ->
            error = "what=$what extra=$extra"
            Log.e(TAG, "MediaPlayer error $error")
            failed.countDown()
            true
        }

        try {
            player.setDataSource(local)
            player.prepareAsync()
            check(prepared.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "the player never prepared (${if (error.isEmpty()) "timeout" else error})"
            }
            check(failed.count == 1L) { "the player reported an error: $error" }

            player.start()
            val duration = player.duration
            val samples = mutableListOf<Int>()
            repeat(3) {
                Thread.sleep(3000)
                samples += player.currentPosition
                Log.i(TAG, "position=${samples.last()}ms of ${duration}ms")
            }
            check(samples.last() > samples.first()) {
                "playback did not advance: $samples (no data flowing through the proxy)"
            }
            Log.i(TAG, "PASS playback advances: $samples")

            // seek：播放器会丢掉当前连接、重新发一个带 Range 的请求 —— 代理必须接得住。
            val target = (duration / 2).coerceAtLeast(60_000)
            player.seekTo(target)
            Thread.sleep(4000)
            val afterSeek = player.currentPosition
            Log.i(TAG, "after seek to ${target}ms -> position=${afterSeek}ms")
            check(afterSeek >= target - SEEK_TOLERANCE_MS) {
                "seek to ${target}ms landed at ${afterSeek}ms"
            }
            Log.i(TAG, "PLAYER PROBE: all checks passed")
        } finally {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
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

    private companion object {
        const val TAG = "PlayerProbe"
        const val PREPARE_TIMEOUT_SECONDS = 90L
        const val SEEK_TOLERANCE_MS = 30_000
    }
}
