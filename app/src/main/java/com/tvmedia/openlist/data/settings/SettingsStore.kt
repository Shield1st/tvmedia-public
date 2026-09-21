package com.tvmedia.openlist.data.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Tiny persistent app settings.
 *
 * SharedPreferences is deliberately enough here — adding DataStore for a few
 * strings would violate the "no unnecessary dependency" rule in the spec.
 *
 * 夸克凭证**不在这里**：它们走 [TokenStore]（加密存储）。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * When true, tapping a video hands the already-resolved direct link to an
     * external player app instead of the built-in ExoPlayer.
     *
     * **默认 true（系统播放器）**：电视上用户多数装了 MPV / VLC 这类播放器，
     * 它们的解码与字幕能力比内置播放器强，所以初次安装的默认值交给外部播放器。
     * 用户显式改过之后就按用户的选择（SharedPreferences 里有值就返回有值）。
     *
     * **写盘用同步 commit**：这是一次罕见的用户操作，但值决定"下次点视频走哪条路"。
     * 用 `apply()`（异步）时，进程若在写盘落地前被系统回收，用户会看到"切换没生效、
     * 要再点一次" —— 设备实测踩到过。同步写一个几十字节的 prefs 文件代价可以忽略。
     */
    var useSystemPlayer: Boolean
        get() = prefs.getBoolean(KEY_USE_SYSTEM_PLAYER, true)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_USE_SYSTEM_PLAYER, value) }

    /**
     * 上次浏览的目录（名字路径，根目录是 `/`）。
     *
     * 用来在**冷启动**（进程被杀、任务被清掉）后把用户送回原来的位置，
     * 而不是丢回根目录 —— 系统播放器在前台时本 app 的任务被回收是很常见的。
     */
    var lastBrowsePath: String
        get() = prefs.getString(KEY_LAST_BROWSE_PATH, null).orEmpty()
        set(value) = prefs.edit {
            if (value.isBlank()) remove(KEY_LAST_BROWSE_PATH) else putString(KEY_LAST_BROWSE_PATH, value)
        }

    /**
     * 上次交给播放器播放的条目路径。
     *
     * 回到列表时焦点落在它上面：用户按一次「下」就到下一集，再按确定即可播放。
     */
    var lastPlayedPath: String
        get() = prefs.getString(KEY_LAST_PLAYED_PATH, null).orEmpty()
        set(value) = prefs.edit {
            if (value.isBlank()) remove(KEY_LAST_PLAYED_PATH) else putString(KEY_LAST_PLAYED_PATH, value)
        }

    private companion object {
        const val PREFS_NAME = "tvmedia_settings"
        const val KEY_USE_SYSTEM_PLAYER = "use_system_player"
        const val KEY_LAST_BROWSE_PATH = "last_browse_path"
        const val KEY_LAST_PLAYED_PATH = "last_played_path"
    }
}
