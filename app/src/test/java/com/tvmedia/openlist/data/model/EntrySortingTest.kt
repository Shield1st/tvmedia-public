package com.tvmedia.openlist.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EntrySortingTest {

    private fun dir(name: String, modified: String) =
        Entry(name = name, path = "/$name", isDir = true, isVideo = false, size = 0L, modified = modified)

    private fun file(name: String, video: Boolean = true) =
        Entry(name = name, path = "/$name", isDir = false, isVideo = video, size = 1L)

    @Test
    fun `folders come first and sort by modification time descending`() {
        val sorted = sortEntries(
            listOf(
                dir("old", "2026-01-01T00:00:00.00+08:00"),
                file("movie.mkv"),
                dir("newest", "2026-09-18T00:33:52.28+08:00"),
                dir("middle", "2026-05-05T12:00:00.00+08:00"),
            ),
        )

        assertEquals(listOf("newest", "middle", "old", "movie.mkv"), sorted.map { it.name })
    }

    @Test
    fun `files sort by natural name order`() {
        val sorted = sortEntries(
            listOf(file("E10.mkv"), file("E02.mkv"), file("E01.mkv")),
        )

        assertEquals(listOf("E01.mkv", "E02.mkv", "E10.mkv"), sorted.map { it.name })
    }

    @Test
    fun `playable files come before the rest`() {
        val sorted = sortEntries(
            listOf(file("notes.txt", video = false), file("movie.mkv"), file("cover.jpg", video = false)),
        )

        assertEquals(listOf("movie.mkv", "cover.jpg", "notes.txt"), sorted.map { it.name })
    }

    @Test
    fun `folder with missing modification time goes last among folders`() {
        val sorted = sortEntries(
            listOf(dir("unknown", ""), dir("known", "2026-01-01T00:00:00.00+08:00")),
        )

        assertEquals(listOf("known", "unknown"), sorted.map { it.name })
    }

    @Test
    fun `full listing mixes folders, episodes and extras`() {
        val sorted = sortEntries(
            listOf(
                file("Show.S01E10.mkv"),
                dir("Season 2", "2026-08-01T00:00:00.00+08:00"),
                file("Show.S01E02.mkv"),
                dir("Season 1", "2026-09-01T00:00:00.00+08:00"),
                file("Show.S01E01.srt", video = false),
            ),
        )

        assertEquals(
            listOf(
                "Season 1",          // folders first, newest modification first
                "Season 2",
                "Show.S01E02.mkv",    // then playable files in natural order
                "Show.S01E10.mkv",
                "Show.S01E01.srt",    // non-playable rows last
            ),
            sorted.map { it.name },
        )
    }
}
