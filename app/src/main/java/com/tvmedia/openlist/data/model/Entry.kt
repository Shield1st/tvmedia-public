package com.tvmedia.openlist.data.model

/** UI-facing directory entry. DTOs never reach the UI layer. */
data class Entry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isVideo: Boolean,
    val size: Long,
    /**
     * Ordering key, produced by the data source and used **only** for sorting.
     *
     * Kept as a string on purpose: `EntrySorting` compares it lexicographically, and the Quark
     * source feeds it `updated_at` as 13-digit epoch milliseconds — a fixed-width numeric string,
     * so string order equals time order. Parsing would need `java.time`, which is unavailable at
     * minSdk 24, for no benefit.
     */
    val modified: String = "",
) {
    /** Videos are the only playable entries; folders are navigable. */
    val isPlayable: Boolean get() = isVideo && !isDir

    /** Non-video files stay visible but must not steal D-Pad focus. */
    val isFocusable: Boolean get() = isDir || isPlayable
}
