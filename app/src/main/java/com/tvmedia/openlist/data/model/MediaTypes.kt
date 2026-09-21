package com.tvmedia.openlist.data.model

/** Extension-based media classification. Kept deliberately small and predictable. */
object MediaTypes {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "ts", "mov", "avi", "flv", "wmv", "webm", "m4v",
        "mpg", "mpeg", "m2ts", "rmvb", "rm", "3gp", "vob", "divx", "f4v", "ogv", "iso",
    )

    fun isVideo(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in VIDEO_EXTENSIONS
    }
}
