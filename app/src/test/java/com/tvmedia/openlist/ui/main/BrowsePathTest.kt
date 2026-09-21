package com.tvmedia.openlist.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 锁住返回键与「恢复上次目录」依赖的父子路径推导。
 *
 * 这里的行为是用户可见的：返回键按一次必须精确回到上一级，恢复目录失败时要能逐级向上回退。
 */
class BrowsePathTest {

    @Test
    fun `the root has no parent`() {
        assertNull(BrowsePath.parentOf(BrowsePath.ROOT))
        assertNull(BrowsePath.parentOf(""))
        assertNull(BrowsePath.parentOf("/"))
    }

    @Test
    fun `a top level entry goes back to the root`() {
        assertEquals(BrowsePath.ROOT, BrowsePath.parentOf("/视频"))
        assertEquals(BrowsePath.ROOT, BrowsePath.parentOf("/视频/"))
    }

    @Test
    fun `deeper paths walk up one level at a time`() {
        assertEquals("/视频", BrowsePath.parentOf("/视频/剧集"))
        assertEquals("/视频/剧集", BrowsePath.parentOf("/视频/剧集/第一季"))
        assertEquals("/视频/剧集", BrowsePath.parentOf("/视频/剧集/第一季/"))
    }

    @Test
    fun `repeatedly walking up always terminates at the root`() {
        // load() 恢复失败时会这样逐级回退，必须能终止（否则会无限递归）。
        var path: String? = "/视频/剧集/第一季/第 1 集.mkv"
        val visited = mutableListOf<String>()
        while (path != null) {
            visited += path
            path = BrowsePath.parentOf(path)
        }
        assertEquals(
            listOf("/视频/剧集/第一季/第 1 集.mkv", "/视频/剧集/第一季", "/视频/剧集", "/视频", "/"),
            visited,
        )
    }
}
