package com.tvmedia.openlist.ui.main

/**
 * 目录路径的纯函数工具（无 Android 依赖，可在 JVM 单测里直接覆盖）。
 *
 * `Entry.path` 是**名字路径**（`/视频/电影/x.mkv`），夸克以 fid 为主键，
 * 这里只做字符串层面的父子推导。
 */
object BrowsePath {

    /** 根目录。与 `QuarkSource.ROOT_PATH` 保持一致。 */
    const val ROOT: String = "/"

    /**
     * 返回 [path] 的父目录；[path] 已经是根目录时返回 null。
     *
     * 只按最后一个 `/` 切分 —— 名字路径里的 `/` 不可能是文件名的一部分（夸克不允许）。
     */
    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash <= 0) ROOT else trimmed.substring(0, lastSlash)
    }
}
