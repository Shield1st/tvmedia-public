package com.tvmedia.openlist.quark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cookie 串的读写。看起来简单，但两处很容易写错：
 *
 * - **同名 cookie 必须替换而不是追加**（出现两次时服务端行为不确定）；
 * - `Set-Cookie` 头里除了 `name=value` 还有 `Path`/`Max-Age` 等属性，必须只取第一段。
 */
class QuarkCookiesTest {

    @Test
    fun `get reads a value and ignores surrounding whitespace`() {
        val cookie = "sid=abc; __puus=token-1;__uid=42"
        assertEquals("abc", QuarkCookies.get(cookie, "sid"))
        assertEquals("token-1", QuarkCookies.get(cookie, "__puus"))
        assertEquals("42", QuarkCookies.get(cookie, "__uid"))
        assertNull(QuarkCookies.get(cookie, "missing"))
    }

    @Test
    fun `set replaces an existing name instead of appending a duplicate`() {
        val updated = QuarkCookies.set("sid=abc; __puus=old", "__puus", "new")

        assertEquals("sid=abc; __puus=new", updated)
        assertEquals(1, updated.split("__puus=").size - 1)
    }

    @Test
    fun `set appends when the name is new`() {
        assertEquals("sid=abc; __puus=new", QuarkCookies.set("sid=abc", "__puus", "new"))
    }

    @Test
    fun `merge lets the second cookie win on conflicts`() {
        val merged = QuarkCookies.merge("sid=web; a=1", "sid=api; b=2")

        assertEquals("api", QuarkCookies.get(merged, "sid"))
        assertEquals("1", QuarkCookies.get(merged, "a"))
        assertEquals("2", QuarkCookies.get(merged, "b"))
    }

    @Test
    fun `hasSession requires the session cookie to be non blank`() {
        assertTrue(QuarkCookies.hasSession("sid=abc; __puus=token"))
        assertFalse(QuarkCookies.hasSession("sid=abc"))
        assertFalse(QuarkCookies.hasSession("__puus="))
        assertFalse(QuarkCookies.hasSession(""))
    }

    @Test
    fun `sessionFromSetCookie only takes the first pair`() {
        val header = "__puus=new-token; Path=/; Domain=.quark.cn; Max-Age=31536000; HttpOnly"

        assertEquals("new-token", QuarkCookies.sessionFromSetCookie(header))
    }

    @Test
    fun `sessionFromSetCookie ignores unrelated cookies`() {
        assertNull(QuarkCookies.sessionFromSetCookie("__pus=other; Path=/"))
        assertNull(QuarkCookies.sessionFromSetCookie("__puus=; Path=/"))
    }
}
