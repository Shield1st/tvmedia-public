package com.tvmedia.openlist.data.source

import android.content.Context

/**
 * UI 取得当前数据源的唯一入口。
 *
 * 本版本只有夸克一个源，所以这里真正的作用是**把「源是否可用」收在一处**：
 * UI 只需要处理「有源 → 浏览」和「没源 → 去扫码」两种情况，不必认识 [QuarkSession]，
 * 也不会持有 `QuarkSource` 这个具体类型（只看到 [MediaSource] 接口）。
 *
 * 返回的源是**进程级单例**（见 [QuarkSession]）：它持有必须跨 Activity 存活的状态
 * —— 只存在内存里的 `access_token` 与浏览过程中填充的「名字路径 → fid」映射。
 * 每次进 Activity 重建会丢掉后者，表现为「目录能列、点进去或点播放却报『目录信息已失效』」。
 */
object MediaSourceRegistry {

    /**
     * 已登录且构造成功时返回当前源；否则返回 **null**（UI 据此引导扫码）。
     *
     * 不抛异常：任何构造失败都只表示"当前没有可用的源"，由调用方决定提示什么。
     */
    fun current(context: Context): MediaSource? = QuarkSession.source(context)
}
