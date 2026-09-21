package com.tvmedia.openlist.data.source

import com.tvmedia.openlist.data.model.Entry

/**
 * UI 唯一依赖的数据源抽象：能列目录、能取播放直链。
 *
 * 本版本只有一个实现（夸克网盘，`quark/QuarkSource.kt`）。保留这层抽象是为了让 UI
 * **完全不认识夸克协议包**：列表 UI、排序、播放分发与预读代理都只消费 [Entry]。
 *
 * 源是**进程级单例**（见 [QuarkSession]）—— 它持有必须跨 Activity 存活的状态：
 * 只存在内存里的 `access_token`，以及浏览过程中填充的「名字路径 → fid」映射。
 */
interface MediaSource {

    /** 列出 [path] 下的条目（文件夹在前，视频优先，其余按自然序）。 */
    suspend fun list(path: String): List<Entry>

    /** 取 [path] 的完整可播放 URL（含认证参数，由数据源负责构造）。 */
    suspend fun resolvePlayUrl(path: String): String

    /**
     * 取 [resolvePlayUrl] 返回的 URL 时**必须额外携带的请求头**。
     *
     * 夸克网页版的 CDN 直链要求带 `Cookie` + `Referer` + `User-Agent`，缺一个就会被拒，
     * 而这三个头只有数据源知道 —— 所以内置播放器与预读代理都要从这里取。
     * 默认没有额外要求。
     */
    fun playbackHeaders(): Map<String, String> = emptyMap()
}
