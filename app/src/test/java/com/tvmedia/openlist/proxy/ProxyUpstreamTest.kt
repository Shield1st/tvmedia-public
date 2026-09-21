package com.tvmedia.openlist.proxy

import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游解析：夸克 CDN 直链的 host 只能从路径前缀里取回来。
 *
 * path 与 query 必须逐字节透传 —— 改写会破坏 CDN 签名参数，或让播放器看不到文件扩展名。
 */
class ProxyUpstreamTest {

    @Test
    fun `origin prefix resolves to the encoded cdn host`() {
        // 固定向量：钉住 origin 的编码格式（hex，不是 base64url）。
        val token = "68747470733a2f2f63646e2e6578616d706c652e636f6d"
        assertEquals(token, "https://cdn.example.com".encodeUtf8().hex())

        assertEquals(
            "https://cdn.example.com/v/1.mp4?auth_key=deadbeef&x=1",
            ProxyUpstream.resolveUpstream(
                "/_u/$token/v/1.mp4",
                "auth_key=deadbeef&x=1",
            ),
        )
    }

    @Test
    fun `origin prefix keeps path and query byte for byte`() {
        val origin = "http://127.0.0.1:5244"
        val token = "687474703a2f2f3132372e302e302e313a35323434"
        assertEquals(token, origin.encodeUtf8().hex())

        val path = "/%E8%A7%86%E9%A2%91/Show%20A/a.mkv"
        val query = "sign=abc%3D%3A0&name=%23tag"
        assertEquals(
            origin + path + "?" + query,
            ProxyUpstream.resolveUpstream(ProxyUpstream.ORIGIN_PREFIX + token + path, query),
        )
    }

    @Test
    fun `no query means no question mark`() {
        val token = "68747470733a2f2f63646e2e6578616d706c652e636f6d"
        assertEquals(
            "https://cdn.example.com/v/1.mp4",
            ProxyUpstream.resolveUpstream("/_u/$token/v/1.mp4", ""),
        )
    }

    @Test
    fun `only the origin prefixed shape is forwardable`() {
        val token = "68747470733a2f2f63646e2e6578616d706c652e636f6d"
        assertTrue(ProxyUpstream.isForwardable("/_u/$token/a.mp4"))
        // 旧 OpenList 的 /d/、/p/ 形态在本分支不再存在：数据源只有夸克，直链一律带 origin。
        assertFalse(ProxyUpstream.isForwardable("/d/a"))
        assertFalse(ProxyUpstream.isForwardable("/p/a"))
        assertFalse(ProxyUpstream.isForwardable("/a/b.mp4"))
        assertFalse(ProxyUpstream.isForwardable("/"))
        assertFalse(ProxyUpstream.isForwardable(""))
        assertFalse(ProxyUpstream.isForwardable("/_u/"))
        assertFalse(ProxyUpstream.isForwardable("/_u/not-hex/a.mp4"))
        assertFalse(ProxyUpstream.isForwardable("/_u/$token"))
    }

    @Test
    fun `origin prefix rejects non http schemes`() {
        val ftp = "ftp://cdn.example.com".encodeUtf8().hex()
        assertFalse(ProxyUpstream.isForwardable("/_u/$ftp/a.mp4"))
        assertNull(ProxyUpstream.resolveUpstream("/_u/$ftp/a.mp4", ""))

        val file = "file:///etc/passwd".encodeUtf8().hex()
        assertFalse(ProxyUpstream.isForwardable("/_u/$file"))
    }

    @Test
    fun `unsupported paths resolve to null instead of guessing an upstream`() {
        assertNull(ProxyUpstream.resolveUpstream("/d/a", "sign=x"))
        assertNull(ProxyUpstream.resolveUpstream("/a/b.mp4", "sign=x"))
        assertNull(ProxyUpstream.resolveUpstream("/", ""))
    }

    @Test
    fun `localPathFor round trips through resolveUpstream`() {
        val original = "https://cdn.example.com/v/1.mp4?auth_key=deadbeef&x=1"
        val localPath = ProxyUpstream.localPathFor(original)
        assertTrue(localPath!!.startsWith(ProxyUpstream.ORIGIN_PREFIX))

        val pathOnly = localPath.substringBefore('?')
        val query = localPath.substringAfter('?', "")
        assertEquals(original, ProxyUpstream.resolveUpstream(pathOnly, query))
    }

    @Test
    fun `localPathFor rejects inputs without a host or path`() {
        assertNull(ProxyUpstream.localPathFor("/d/x.mkv"))
        assertNull(ProxyUpstream.localPathFor("not a url"))
        assertNull(ProxyUpstream.localPathFor("https://host"))
    }

    @Test
    fun `origin token length is bounded`() {
        val huge = "https://".plus("a".repeat(600)).plus(".com").encodeUtf8().hex()
        assertFalse(ProxyUpstream.isForwardable("/_u/$huge/a.mp4"))
    }
}
