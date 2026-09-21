package com.tvmedia.openlist.quark

import com.tvmedia.openlist.data.source.MediaSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 锁住 `QuarkSource` 这个适配层：
 *
 * - `Entry.path` 必须是**名字路径**（`/视频/电影.mkv`），因为 `MainActivity` 按 `/` 切它做返回、
 *   并直接把它当面包屑显示；
 * - fid 只在内部映射里流转，且进入子目录时必须复用上一步记下的 fid；
 * - 目录信息失效时要抛可读异常，而不是构造注定失败的 URL；
 * - 直链**不缓存**（夸克的是带时效的签名 CDN 链接）；
 * - 取直链所需的请求头要透传给播放器与直通代理。
 */
class QuarkSourceTest {

    private fun newSource(
        vararg responses: QuarkResponse,
    ): Pair<QuarkSource, FakeQuarkTransport> {
        val transport = FakeQuarkTransport(*responses)
        return QuarkSource(QuarkApiClient(FakeQuarkCookieHolder(), transport)) to transport
    }

    @Test
    fun `list builds name paths from the root and keeps folders first`() = runBlocking {
        val (source, transport) = newSource(QuarkResponse(200, rootPage()))

        val entries = source.list(QuarkSource.ROOT_PATH)

        assertEquals(listOf("视频", "来自：分享"), entries.map { it.name })
        assertEquals(listOf("/视频", "/来自：分享"), entries.map { it.path })
        assertTrue(entries.all { it.isDir })
        assertTrue(entries.none { it.isVideo })
        // 根目录必须用 fid "0"（参考实现的 DefaultRoot）
        assertEquals("0", transport.requests.single().url.queryParameter("pdir_fid"))
    }

    @Test
    fun `entering a subdirectory reuses the fid remembered from the parent listing`() = runBlocking {
        val (source, transport) = newSource(
            QuarkResponse(200, rootPage()),
            QuarkResponse(200, videoDirPage()),
        )

        source.list(QuarkSource.ROOT_PATH)
        val inside = source.list("/视频")

        assertEquals(
            "171d0fe29dc749a392b5d1a52b00dd48",
            transport.requests[1].url.queryParameter("pdir_fid"),
        )
        // 文件夹在前；文件里视频优先，其余按自然序；隐藏文件被过滤
        assertEquals(listOf("剧集", "电影.mkv", "说明.txt"), inside.map { it.name })
        assertEquals(
            listOf("/视频/剧集", "/视频/电影.mkv", "/视频/说明.txt"),
            inside.map { it.path },
        )
        assertTrue(inside[0].isDir)
        assertTrue(inside[1].isVideo)
        assertFalse(inside[2].isVideo)
        assertEquals(100L, inside[1].size)
        assertEquals("1000", inside[1].modified)
    }

    @Test
    fun `resolvePlayUrl maps a name path back to its fid and returns the cdn link`() = runBlocking {
        val cdn = "https://cdn.quark.cn/v/%E7%94%B5%E5%BD%B1.mkv?auth_key=abc&t=1"
        val (source, transport) = newSource(
            QuarkResponse(200, rootPage()),
            QuarkResponse(200, videoDirPage()),
            QuarkResponse(200, """{"status":0,"code":0,"data":[{"download_url":"$cdn"}]}"""),
        )

        source.list(QuarkSource.ROOT_PATH)
        source.list("/视频")
        val url = source.resolvePlayUrl("/视频/电影.mkv")

        assertEquals(cdn, url)
        val request = transport.requests[2]
        assertEquals("POST", request.method)
        assertEquals("/1/clouddrive/file/download", request.url.encodedPath)
    }

    @Test
    fun `resolvePlayUrl does not cache, because cdn links expire`() = runBlocking {
        val (source, transport) = newSource(
            QuarkResponse(200, rootPage()),
            QuarkResponse(200, videoDirPage()),
            QuarkResponse(200, """{"status":0,"code":0,"data":[{"download_url":"https://cdn/1"}]}"""),
            QuarkResponse(200, """{"status":0,"code":0,"data":[{"download_url":"https://cdn/2"}]}"""),
        )

        source.list(QuarkSource.ROOT_PATH)
        source.list("/视频")
        assertEquals("https://cdn/1", source.resolvePlayUrl("/视频/电影.mkv"))
        // 第二次必须重新取（缓存会在链接过期后静默播不动）
        assertEquals("https://cdn/2", source.resolvePlayUrl("/视频/电影.mkv"))
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun `an unknown path fails with an actionable message instead of a broken url`() = runBlocking {
        // 映射为空时源会从根逐级解析：根目录里没有这个名字 → 抛可读异常。
        val (source, transport) = newSource(QuarkResponse(200, rootPage()))

        try {
            source.list("/从没列过的目录")
            fail("expected QuarkProtocolException")
        } catch (e: QuarkProtocolException) {
            assertTrue(e.message!!.contains("重新进入"))
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a cold source resolves a deep path by walking from the root`() = runBlocking {
        val cdn = "https://cdn.quark.cn/v/%E7%94%B5%E5%BD%B1.mkv?auth_key=abc"
        // 冷启动：没有先 list("/")，映射是空的 —— 必须自己从根走下来，而不是报「已失效」。
        val (source, transport) = newSource(
            QuarkResponse(200, rootPage()),
            QuarkResponse(200, videoDirPage()),
            QuarkResponse(200, """{"status":0,"code":0,"data":[{"download_url":"$cdn"}]}"""),
        )

        val url = source.resolvePlayUrl("/视频/电影.mkv")

        assertEquals(cdn, url)
        assertEquals("0", transport.requests[0].url.queryParameter("pdir_fid"))
        assertEquals(
            "171d0fe29dc749a392b5d1a52b00dd48",
            transport.requests[1].url.queryParameter("pdir_fid"),
        )
        assertEquals("POST", transport.requests[2].method)
    }

    @Test
    fun `a walk that hits a missing segment still fails readably`() = runBlocking {
        // 根目录能列、也能走到「视频」，但「视频」下面没有叫「电影」的目录
        // （只有 电影.mkv 这个文件）→ 必须报可读错误，而不是构造一个注定失败的请求。
        val (source, transport) = newSource(
            QuarkResponse(200, rootPage()),
            QuarkResponse(200, videoDirPage()),
        )

        try {
            source.list("/视频/电影")
            fail("expected QuarkProtocolException")
        } catch (e: QuarkProtocolException) {
            assertTrue(e.message!!.contains("重新进入"))
        }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `playback headers are forwarded for the player and the proxy`() {
        val (source, _) = newSource()

        val headers = source.playbackHeaders()

        assertEquals(QuarkProtocol.REFERER, headers["Referer"])
        assertEquals(QuarkProtocol.USER_AGENT, headers["User-Agent"])
        assertEquals("sid=test; __puus=session-token", headers["Cookie"])
    }

    @Test
    fun `the source is usable through the MediaSource interface the UI depends on`() = runBlocking {
        val (source, _) = newSource(QuarkResponse(200, rootPage()))

        // 编译期就要求 QuarkSource 实现 MediaSource；这里真正走一遍接口路径。
        val mediaSource: MediaSource = source

        assertEquals(listOf("视频", "来自：分享"), mediaSource.list("/").map { it.name })
    }

    // --- fixtures ------------------------------------------------------------

    private fun rootPage(): String = page(
        listOf(
            """{"fid":"171d0fe29dc749a392b5d1a52b00dd48","file_name":"视频","file":false,""" +
                """"category":0,"size":0,"updated_at":1789677376156}""",
            """{"fid":"78fa02876c0540608dd5cfa13d3d64b9","file_name":"来自：分享","file":false,""" +
                """"category":0,"size":0,"updated_at":1789662064359}""",
        ),
        total = 2,
    )

    private fun videoDirPage(): String = page(
        listOf(
            // 文件夹（updated_at 最大 → 排最前）
            """{"fid":"2222222222222222222222222222bbbb","file_name":"剧集","file":false,""" +
                """"category":0,"size":0,"updated_at":2000}""",
            // 视频：category=1
            """{"fid":"1111111111111111111111111111aaaa","file_name":"电影.mkv","file":true,""" +
                """"category":1,"size":100,"updated_at":1000}""",
            // 非视频文件
            """{"fid":"3333333333333333333333333333cccc","file_name":"说明.txt","file":true,""" +
                """"category":0,"size":5,"updated_at":3000}""",
            // 隐藏文件：必须被过滤掉
            """{"fid":"4444444444444444444444444444dddd","file_name":".DS_Store","file":true,""" +
                """"category":0,"size":1,"updated_at":4000}""",
        ),
        total = 4,
    )

    private fun page(files: List<String>, total: Int): String =
        """{"status":0,"code":0,"data":{"list":[${files.joinToString(",")}]},""" +
            """"metadata":{"_size":100,"_page":1,"_count":${files.size},"_total":$total}}"""
}
