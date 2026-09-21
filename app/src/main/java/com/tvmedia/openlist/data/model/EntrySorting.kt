package com.tvmedia.openlist.data.model

/**
 * Listing order:
 *
 * 1. folders first, **newest modification first**
 * 2. then files by **natural name order** (`E02` before `E10`, `2` before `10`)
 * 3. playable files ahead of the rest (non-video rows are not focusable, so this keeps the
 *    D-Pad path free of dead stops)
 *
 * The order is computed locally rather than trusting the server, so it does not depend on the
 * backing storage or on what order the API happens to return.
 */
internal fun sortEntries(entries: List<Entry>): List<Entry> {
    val (directories, files) = entries.partition { it.isDir }
    val sortedDirectories = directories.sortedWith(compareByDescending { it.modified })
    val sortedFiles = files.sortedWith(
        compareByDescending<Entry> { it.isPlayable }
            .thenComparator { left, right -> NaturalOrder.compare(left.name, right.name) },
    )
    return sortedDirectories + sortedFiles
}
