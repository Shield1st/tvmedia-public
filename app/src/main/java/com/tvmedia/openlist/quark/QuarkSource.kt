package com.tvmedia.openlist.quark

import com.tvmedia.openlist.data.model.Entry
import com.tvmedia.openlist.data.model.sortEntries
import com.tvmedia.openlist.data.source.MediaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * 夸克网盘数据源：把 [QuarkApiClient] 适配成 UI 唯一依赖的 [MediaSource]。
 *
 * 列表 UI、排序、播放分发与预读代理**全部零改动** —— 它们只消费 [Entry]。
 *
 * ### 为什么 `Entry.path` 是"名字路径"而不是 fid
 *
 * 夸克以 fid 为主键，没有"按路径查 fid"的接口，用 fid 当 `Entry.path` 本来最省事。
 * 但 `MainActivity.parentOf()` 按 `/` 切 `path` 实现返回键、`displayPath()` 直接把 `path`
 * 显示成面包屑 —— 塞 fid 进去会让面包屑变成一串 32 位哈希，违背"浏览体验与现在完全一致"。
 *
 * 所以这里用 `/视频/电影/x.mkv` 这样的名字路径，fid 只在本类内部的 [fidByPath] 里流转。
 *
 * ### 映射缺失时会自己走一遍路径
 *
 * 映射由 [list] 填充，正常浏览时总是先列父目录再进子目录，所以总是命中。
 * 但**冷启动**（进程被杀后重新打开 app）时映射是空的，而 UI 会恢复到上次浏览的深层目录 ——
 * 这时 [fidFor] 会退化为 [resolveByWalking]：从根 fid 开始逐级 `file/sort` 找到目标目录，
 * 顺带把沿途条目补进映射。代价是深度 N 的路径多 N 次列目录请求，只在映射缺失时发生。
 *
 * 任一层找不到同名目录 → 抛可读异常（目录确实没了），由 UI 逐级向上回退。
 */
class QuarkSource(
    private val api: QuarkApiClient,
) : MediaSource {

    /** 名字路径 → fid。生命周期与源（进程级单例）一致，规模由用户实际浏览过的目录决定。 */
    private val fidByPath = ConcurrentHashMap<String, String>()

    override suspend fun list(path: String): List<Entry> {
        val parentFid = fidFor(path, requireDirectory = true)
        return sortEntries(
            api.list(parentFid).mapNotNull { file ->
                val name = file.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (name.startsWith(".")) return@mapNotNull null
                val childPath = joinPath(path, name)
                fidByPath[childPath] = file.fid
                Entry(
                    name = name,
                    path = childPath,
                    isDir = file.isDir,
                    isVideo = file.isVideo,
                    size = file.size,
                    modified = file.modified,
                )
            },
        )
    }

    /**
     * 取播放直链。
     *
     * **刻意不缓存**：夸克的 `download_url` 是带时效的签名 CDN 链接，缓存下来会在过期后
     * 静默播不动。宁可每次点多播一个文件时多换一次直链。
     */
    override suspend fun resolvePlayUrl(path: String): String =
        api.downloadUrl(fidFor(path, requireDirectory = false))

    /**
     * 夸克网页版的 CDN 直链要求带 Cookie + Referer + UA —— 内置播放器与预读代理都要用。
     */
    override fun playbackHeaders(): Map<String, String> = api.playbackHeaders()

    /**
     * 名字路径 → fid。
     *
     * 命中直接返回；未命中则从根逐级解析（冷启动恢复深层目录的唯一办法）。
     *
     * [requireDirectory] 区分两种调用：列目录要求路径本身是目录，取直链时最后一段是文件。
     */
    private suspend fun fidFor(path: String, requireDirectory: Boolean): String {
        if (path == ROOT_PATH) return ROOT_FID
        fidByPath[path]?.let { return it }
        return resolveByWalking(path, requireDirectory)
    }

    /**
     * 从根目录开始逐级列出，把 [path] 解析成 fid，并把沿途条目补进 [fidByPath]。
     *
     * 中间每一层都必须是**同名目录**；最后一段在 [requireDirectory] 为 false 时允许是文件
     * （`resolvePlayUrl` 的目标本来就是文件）。任一层找不到 → 抛异常交给 UI 回退，
     * **绝不构造注定失败的 URL**。
     */
    private suspend fun resolveByWalking(path: String, requireDirectory: Boolean): String {
        var fid = ROOT_FID
        var walked = ROOT_PATH
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            val siblings = api.list(fid)
            siblings.forEach { file ->
                val name = file.name.takeIf { it.isNotBlank() } ?: return@forEach
                fidByPath[joinPath(walked, name)] = file.fid
            }
            val next = siblings.firstOrNull { file ->
                file.name == segment && (file.isDir || (isLast && !requireDirectory))
            } ?: throw QuarkProtocolException(staleMessage(path))
            walked = joinPath(walked, segment)
            fid = next.fid
            fidByPath[walked] = fid
        }
        return fid
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.endsWith("/")) parent + name else "$parent/$name"

    private fun staleMessage(path: String): String =
        "夸克目录信息已失效（$path），请返回上一级重新进入"

    companion object {

        /** 参考实现 `meta.go` 的 `DefaultRoot: "0"` —— 用户网盘根目录，不需要额外配置项。 */
        const val ROOT_FID: String = "0"

        /** 与 `BrowsePath.ROOT` 一致。 */
        const val ROOT_PATH: String = "/"
    }
}
