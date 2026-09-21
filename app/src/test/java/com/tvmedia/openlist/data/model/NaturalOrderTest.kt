package com.tvmedia.openlist.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Episode ordering lives or dies on this comparator: a plain string sort would put `E10`
 * before `E02`, which is the classic "episode 10 plays after episode 1" bug.
 */
class NaturalOrderTest {

    private fun sorted(vararg names: String): List<String> = names.sortedWith(NaturalOrder)

    @Test
    fun `digits compare by value not by character`() {
        assertEquals(listOf("2", "10", "100"), sorted("10", "100", "2"))
    }

    @Test
    fun `episode numbers inside a name`() {
        assertEquals(
            listOf("Show.S01E02.mkv", "Show.S01E10.mkv", "Show.S02E01.mkv"),
            sorted("Show.S01E10.mkv", "Show.S02E01.mkv", "Show.S01E02.mkv"),
        )
    }

    @Test
    fun `chinese episode markers`() {
        assertEquals(listOf("第2集", "第10集", "第20集"), sorted("第10集", "第20集", "第2集"))
    }

    @Test
    fun `case insensitive`() {
        assertEquals(0, NaturalOrder.compare("abc", "ABC"))
        assertEquals(0, NaturalOrder.compare("Show", "sHOW"))
    }

    @Test
    fun `shorter string sorts first when it is a prefix`() {
        assertTrue(NaturalOrder.compare("a", "ab") < 0)
    }

    @Test
    fun `leading zeros do not change the value`() {
        assertEquals(0, NaturalOrder.compare("E007", "E7"))
        assertEquals(0, NaturalOrder.compare("007", "7"))
    }

    @Test
    fun `digit run followed by more text`() {
        assertEquals(listOf("a1b", "a2b", "a10b"), sorted("a10b", "a1b", "a2b"))
    }

    @Test
    fun `mixed separators and quality tags`() {
        assertEquals(
            listOf("S01E01.1080p", "S01E01.2160p", "S01E02.1080p"),
            sorted("S01E02.1080p", "S01E01.2160p", "S01E01.1080p"),
        )
    }

    @Test
    fun `identical strings compare equal`() {
        assertEquals(0, NaturalOrder.compare("same", "same"))
    }
}
