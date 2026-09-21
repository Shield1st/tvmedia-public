package com.tvmedia.openlist.quark

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件名反转义。参考实现用 Go 的 `html.UnescapeString`；这里用一段等价的小实现，
 * 因为安卓的 `Html.fromHtml` 在纯 JVM 单测里跑不起来（而且它还会顺手把标签也处理掉）。
 */
class QuarkHtmlTest {

    @Test
    fun `decodes the named entities that actually appear in file names`() {
        assertEquals("A&B.mkv", QuarkHtml.unescape("A&amp;B.mkv"))
        assertEquals("<tag>.mp4", QuarkHtml.unescape("&lt;tag&gt;.mp4"))
        assertEquals("say \"hi\"", QuarkHtml.unescape("say &quot;hi&quot;"))
        assertEquals("it's", QuarkHtml.unescape("it&apos;s"))
    }

    @Test
    fun `decodes numeric and hex references`() {
        assertEquals("A\u00B7B", QuarkHtml.unescape("A&#183;B"))
        assertEquals("A\u00B7B", QuarkHtml.unescape("A&#xB7;B"))
        // 中文用实体编码时也要还原
        assertEquals("视频", QuarkHtml.unescape("&#35270;&#39057;"))
    }

    @Test
    fun `leaves unknown entities and plain text alone`() {
        assertEquals("&unknown;", QuarkHtml.unescape("&unknown;"))
        assertEquals("a & b", QuarkHtml.unescape("a & b"))
        assertEquals("普通文件名.mkv", QuarkHtml.unescape("普通文件名.mkv"))
    }

    @Test
    fun `a string without ampersands is returned as is`() {
        val name = "Show S01E01 [1080p].mkv"
        assertEquals(name, QuarkHtml.unescape(name))
    }
}
