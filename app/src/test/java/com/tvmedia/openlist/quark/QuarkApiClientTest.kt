package com.tvmedia.openlist.quark

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 锁住夸克**网页版**数据能力：列目录、取直链、会话续期。
 *
 * 重点盯住几处「和 TV 端不一样、写错就会静默出问题」的地方：
 * 字段名是 `file_name` 而不是 `filename`；目录是 `file: false` 而不是 `isdir: 1`；
 * 取直链必须带 Cookie/Referer/UA。
 */
class QuarkApiClientTest {

    private fun newClient(
        vararg responses: QuarkResponse,
    ): Triple<QuarkApiClient, FakeQuarkTransport, FakeQuarkCookieHolder> {
        val transport = FakeQuarkTransport(*responses)
        val cookies = FakeQuarkCookieHolder()
        return Triple(QuarkApiClient(cookies, transport), transport, cookies)
    }

    // --- list ---------------------------------------------------------------

    @Test
    fun `list maps the web field names`() = runBlocking {
        val (client, transport, _) = newClient(QuarkResponse(200, page(ROOT_FILES, total = 2)))

        val files = client.list(QuarkSource.ROOT_FID)

        assertEquals(2, files.size)
        val video = files[0]
        assertEquals("171d0fe29dc749a392b5d1a52b00dd48", video.fid)
        assertEquals("视频", video.name)
        // 网页版：file == false 表示目录
        assertTrue(video.isDir)
        assertFalse(video.isVideo)
        assertEquals(0L, video.size)
        assertEquals("1789677376156", video.modified)

        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/1/clouddrive/file/sort", request.url.encodedPath)
        assertTrue(request.url.toString().startsWith(QuarkProtocol.API_BASE + "/file/sort?"))
        assertEquals("ucpro", request.url.queryParameter("pr"))
        assertEquals("pc", request.url.queryParameter("fr"))
        assertEquals(QuarkSource.ROOT_FID, request.url.queryParameter("pdir_fid"))
        assertEquals("100", request.url.queryParameter("_size"))
        assertEquals("1", request.url.queryParameter("_page"))
        assertEquals("1", request.url.queryParameter("_fetch_total"))
        assertEquals("1", request.url.queryParameter("fetch_all_file"))
        assertEquals("1", request.url.queryParameter("fetch_risk_file_name"))
        // 网页版的三件套：Cookie / Referer / UA，缺一个都会被拒
        assertEquals(QuarkProtocol.REFERER, request.header("Referer"))
        assertEquals(QuarkProtocol.USER_AGENT, request.header("User-Agent"))
        assertEquals("sid=test; __puus=session-token", request.header("Cookie"))
    }

    @Test
    fun `list unescapes html entities in file names`() = runBlocking {
        val body = page(
            listOf(file("f1", "A&amp;B &lt;1080p&gt;.mkv", isFile = true, category = 1)),
            total = 1,
        )
        val (client, _, _) = newClient(QuarkResponse(200, body))

        assertEquals("A&B <1080p>.mkv", client.list("0").single().name)
    }

    @Test
    fun `video detection unions category and extension`() = runBlocking {
        val body = page(
            listOf(
                file("f1", "a.bin", isFile = true, category = 1),
                file("f2", "b.mkv", isFile = true, category = 0),
                file("f3", "c.txt", isFile = true, category = 0),
                file("f4", "d", isFile = false, category = 1),
            ),
            total = 4,
        )
        val (client, _, _) = newClient(QuarkResponse(200, body))

        val byFid = client.list("0").associateBy { it.fid }

        assertTrue(byFid.getValue("f1").isVideo)
        assertTrue(byFid.getValue("f2").isVideo)
        assertFalse(byFid.getValue("f3").isVideo)
        // 目录即使 category=1 也不是"视频"
        assertTrue(byFid.getValue("f4").isDir)
        assertFalse(byFid.getValue("f4").isVideo)
    }

    @Test
    fun `entries without fid or file_name are dropped instead of failing the page`() = runBlocking {
        val body = page(
            listOf(
                """{"file_name":"no-fid","file":true}""",
                """{"fid":"no-name","file":true}""",
                file("ok", "good.mkv", isFile = true, category = 1),
            ),
            total = 3,
        )
        val (client, _, _) = newClient(QuarkResponse(200, body))

        assertEquals(listOf("ok"), client.list("0").map { it.fid })
    }

    @Test
    fun `missing updated_at becomes an empty modified so it sorts last`() = runBlocking {
        val body = page(listOf("""{"fid":"f1","file_name":"a.mkv","file":true}"""), total = 1)
        val (client, _, _) = newClient(QuarkResponse(200, body))

        assertEquals("", client.list("0").single().modified)
    }

    @Test
    fun `pagination stops once the page covers _total`() = runBlocking {
        val body = page(listOf(file("f1", "a.mkv", isFile = true, category = 1)), total = 1)
        val (client, transport, _) = newClient(QuarkResponse(200, body))

        assertEquals(1, client.list("0").size)
        // _size=100 且 _total=1 → 第一页就该停
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `pagination continues while _total is not covered`() = runBlocking {
        val (client, transport, _) = newClient(
            QuarkResponse(200, page(listOf(file("f1", "a.mkv", isFile = true, category = 1)), total = 200)),
            QuarkResponse(200, page(listOf(file("f2", "b.mkv", isFile = true, category = 1)), total = 200)),
        )

        assertEquals(2, client.list("0").size)
        assertEquals(2, transport.requests.size)
        assertEquals("1", transport.requests[0].url.queryParameter("_page"))
        assertEquals("2", transport.requests[1].url.queryParameter("_page"))
    }

    @Test
    fun `an empty page ends pagination even when _total lies`() = runBlocking {
        val (client, transport, _) = newClient(
            QuarkResponse(200, page(listOf(file("f1", "a.mkv", isFile = true, category = 1)), total = 999_999)),
            QuarkResponse(200, page(emptyList(), total = 999_999)),
        )

        assertEquals(1, client.list("0").size)
        assertEquals(2, transport.requests.size)
    }

    // --- downloadUrl --------------------------------------------------------

    @Test
    fun `downloadUrl posts the fid and returns the cdn link`() = runBlocking {
        val cdn = "https://cdn.quark.cn/v/1.mp4?auth_key=deadbeef&t=1"
        val (client, transport, _) = newClient(
            QuarkResponse(200, """{"status":0,"code":0,"data":[{"download_url":"$cdn"}]}"""),
        )

        assertEquals(cdn, client.downloadUrl("fid-9"))

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/1/clouddrive/file/download", request.url.encodedPath)
        assertEquals(QuarkProtocol.REFERER, request.header("Referer"))
        assertEquals(QuarkProtocol.USER_AGENT, request.header("User-Agent"))
        val body = request.bodyText()
        assertEquals("""{"fids":["fid-9"]}""", body)
    }

    @Test
    fun `downloadUrl fails loudly when the server omits the link`() = runBlocking {
        val (client, _, _) = newClient(QuarkResponse(200, """{"status":0,"code":0,"data":[]}"""))

        try {
            client.downloadUrl("fid-9")
            fail("expected QuarkProtocolException")
        } catch (e: QuarkProtocolException) {
            assertTrue(e.message!!.contains("未返回下载直链"))
        }
    }

    // --- 错误层次 -----------------------------------------------------------

    @Test
    fun `a business code surfaces the server message`() = runBlocking {
        val (client, _, _) = newClient(
            QuarkResponse(200, """{"status":400,"code":31001,"message":"未登录"}"""),
        )

        try {
            client.list("0")
            fail("expected QuarkProtocolException")
        } catch (e: QuarkProtocolException) {
            assertTrue(e.message!!.contains("未登录"))
        }
    }

    @Test
    fun `a non json response reports the http status`() = runBlocking {
        val (client, _, _) = newClient(QuarkResponse(403, "<html>blocked</html>"))

        try {
            client.list("0")
            fail("expected QuarkProtocolException")
        } catch (e: QuarkProtocolException) {
            assertTrue(e.message!!.contains("403"))
            assertFalse("raw body must not be echoed", e.message!!.contains("blocked"))
        }
    }

    // --- 会话续期 -----------------------------------------------------------

    @Test
    fun `a renewed session cookie is merged back into the stored cookie`() = runBlocking {
        val (client, _, cookies) = newClient(
            QuarkResponse(
                200,
                page(emptyList(), total = 0),
                setCookies = listOf("__puus=renewed-token; Path=/; Domain=.quark.cn; HttpOnly"),
            ),
        )

        client.list("0")

        assertEquals("renewed-token", QuarkCookies.get(cookies.cookie, QuarkProtocol.COOKIE_SESSION))
        // 其它字段不能丢
        assertEquals("test", QuarkCookies.get(cookies.cookie, "sid"))
    }

    @Test
    fun `an unrelated set cookie is ignored`() = runBlocking {
        val (client, _, cookies) = newClient(
            QuarkResponse(200, page(emptyList(), total = 0), setCookies = listOf("__pus=other; Path=/")),
        )

        client.list("0")

        assertEquals("session-token", QuarkCookies.get(cookies.cookie, QuarkProtocol.COOKIE_SESSION))
    }

    // --- 探活与播放请求头 ---------------------------------------------------

    @Test
    fun `verifySession succeeds when the cookie can list the root`() = runBlocking {
        val (client, transport, _) = newClient(QuarkResponse(200, page(emptyList(), total = 0)))

        client.verifySession()

        assertEquals(QuarkSource.ROOT_FID, transport.requests.single().url.queryParameter("pdir_fid"))
    }

    @Test
    fun `verifySession propagates the failure so the login page can keep waiting`() = runBlocking {
        val (client, _, _) = newClient(
            QuarkResponse(200, """{"status":400,"code":31001,"message":"未登录"}"""),
        )

        try {
            client.verifySession()
            fail("expected QuarkProtocolException")
        } catch (e: QuarkProtocolException) {
            assertTrue(e.message!!.contains("未登录"))
        }
    }

    @Test
    fun `playbackHeaders carry cookie referer and user agent`() {
        val (client, _, _) = newClient()

        val headers = client.playbackHeaders()

        assertEquals("sid=test; __puus=session-token", headers["Cookie"])
        assertEquals(QuarkProtocol.REFERER, headers["Referer"])
        assertEquals(QuarkProtocol.USER_AGENT, headers["User-Agent"])
    }

    // --- helpers ------------------------------------------------------------

    private fun page(files: List<String>, total: Int): String =
        """{"status":0,"code":0,"data":{"list":[${files.joinToString(",")}]},""" +
            """"metadata":{"_size":100,"_page":1,"_count":${files.size},"_total":$total}}"""

    private fun file(fid: String, name: String, isFile: Boolean, category: Int): String =
        """{"fid":"$fid","file_name":"$name","file":$isFile,"category":$category,""" +
            """"size":10,"updated_at":1700000000000}"""

    private fun Request.bodyText(): String {
        val body = requireNotNull(this.body) { "request has no body" }
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        /** 网页版的根目录响应形状（字段名与类型都按参考实现）。 */
        val ROOT_FILES = listOf(
            """{"fid":"171d0fe29dc749a392b5d1a52b00dd48","file_name":"视频","file":false,""" +
                """"category":0,"size":0,"updated_at":1789677376156}""",
            """{"fid":"78fa02876c0540608dd5cfa13d3d64b9","file_name":"来自：分享","file":false,""" +
                """"category":0,"size":0,"updated_at":1789662064359}""",
        )
    }
}
