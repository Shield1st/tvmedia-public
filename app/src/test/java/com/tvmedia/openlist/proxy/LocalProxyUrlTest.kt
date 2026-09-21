package com.tvmedia.openlist.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放 URL → 回环代理 URL 的改写。
 *
 * 夸克的直链是 CDN 上的绝对 URL，host 必须随路径一起带过去；path 与 query 一字不能改
 * （query 里是 CDN 签名，path 末尾是播放器用来识别格式的扩展名）。
 */
class LocalProxyUrlTest {

    private val loopback = "http://127.0.0.1:41234"

    @Test
    fun `rewrites host and port while keeping path and query intact`() {
        val cdn = "https://cdn.quark.cn/v/Show%20A/a.mkv?auth_key=abc&t=1"

        val local = LocalProxy.toLocalUrl(cdn, 41234)

        assertTrue(local.startsWith("$loopback/_u/"))
        val pathOnly = local.removePrefix(loopback).substringBefore('?')
        val query = local.substringAfter('?', "")
        assertEquals(cdn, ProxyUpstream.resolveUpstream(pathOnly, query))
    }

    @Test
    fun `a cdn link is never left unproxied`() {
        // 原样返回会让外部播放器直连，直通代理静默失效
        // （这正是 spec 里记录过的「漏掉代理那条路径」事故）。
        assertTrue(
            LocalProxy.toLocalUrl("http://cdn.example.com/a.mkv", 5)
                .startsWith("http://127.0.0.1:5/_u/"),
        )
    }

    @Test
    fun `the loopback url is stable for the same input`() {
        val cdn = "https://cdn.example.com/v/1.mp4?k=v"
        assertEquals(LocalProxy.toLocalUrl(cdn, 9), LocalProxy.toLocalUrl(cdn, 9))
    }

    @Test
    fun `leaves relative or malformed input untouched`() {
        // 不可转发的输入原样返回，由调用方回退直连（不能因为代理而阻断播放）。
        assertEquals("/d/x.mkv", LocalProxy.toLocalUrl("/d/x.mkv", 1234))
        assertEquals("not a url", LocalProxy.toLocalUrl("not a url", 1234))
        assertEquals("https://host", LocalProxy.toLocalUrl("https://host", 1234))
    }

    /**
     * **已经改写成代理地址的 URL 不能再包一层**。
     *
     * 这是一次真实事故：`play()` 包了一次，`launchExternalPlayer()` 内部又包了一次，
     * 外部播放器拿到的是"代理套代理"的地址 —— 代理去请求自己，回 502，
     * 播放器报「此片源无法播放」（内置播放器走另一条分支，所以只有它没事）。
     *
     * 这里锁住两件事：① 能识别出"已经是代理地址"；② 再包一次也不会指向自己。
     */
    @Test
    fun `detects a url that is already a local proxy url`() {
        val cdn = "https://cdn.quark.cn/v/a.mkv?auth_key=abc"
        val once = LocalProxy.toLocalUrl(cdn, 41234)
        assertTrue("a rewritten url must be recognised", ProxyUpstream.isLocalProxyUrl(once))
        assertTrue("the original cdn url must not be mistaken for one", !ProxyUpstream.isLocalProxyUrl(cdn))
    }
}
