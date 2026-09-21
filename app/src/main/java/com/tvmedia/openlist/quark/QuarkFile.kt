package com.tvmedia.openlist.quark

import com.google.gson.JsonObject
import com.tvmedia.openlist.data.model.MediaTypes

/**
 * 夸克 `GET /file/sort` 返回的一个条目（**网页版字段名**）。
 *
 * 这是协议层模型，不是 UI 模型 —— 由 [QuarkSource] 转换成 `Entry`，UI 永远看不到它。
 *
 * 注意与 TV 端的差异（踩过的坑）：
 * - 文件名是 **`file_name`**（TV 端是 `filename`），而且**必须做 HTML 反转义**；
 * - 目录判断是 **`file: false`**（TV 端是 `isdir: 1`）—— 语义正好相反；
 * - `updated_at` 同样是 epoch 毫秒。
 */
data class QuarkFile(
    /** 夸克的主键。目录与文件都用它标识，**不是路径**。 */
    val fid: String,
    val name: String,
    val isDir: Boolean,
    val isVideo: Boolean,
    val size: Long,
    /** `updated_at` 的字符串形式（epoch 毫秒），供 `EntrySorting` 做字符串比较；缺失时为 `""`。 */
    val modified: String,
) {
    internal companion object {

        /** 实测：`category == 1` 表示视频。 */
        private const val CATEGORY_VIDEO = 1

        /**
         * 从服务端 JSON 构造；缺 `fid` 或 `file_name` 的条目直接丢弃
         * （无法使用，也不该让整页失败）。
         *
         * `isVideo` 用**两个信号取并集**：`category == 1` 是夸克对视频的权威标记，
         * 但若某个容器格式被标成别的 category，扩展名判断能兜住 —— 否则它会变成不可聚焦的项，
         * 在 D-Pad 上造成"看得见但选不中"的死停。
         */
        fun from(json: JsonObject): QuarkFile? {
            val fid = json.stringOrEmpty("fid").takeIf { it.isNotBlank() } ?: return null
            val name = QuarkHtml.unescape(json.stringOrEmpty("file_name"))
                .takeIf { it.isNotBlank() } ?: return null
            // 网页版：file == true 表示「是文件」，所以目录是 !file（照搬参考实现的 IsDir()）。
            val isDir = !json.booleanOrFalse("file")
            val updatedAt = json.longOrZero("updated_at")
            return QuarkFile(
                fid = fid,
                name = name,
                isDir = isDir,
                isVideo = !isDir &&
                    (json.intOrZero("category") == CATEGORY_VIDEO || MediaTypes.isVideo(name)),
                size = json.longOrZero("size"),
                // 13 位毫秒值在 2001–2286 年区间长度一致，字典序等于数值序，
                // 正好满足 EntrySorting 对 modified 的字符串比较约定。
                modified = if (updatedAt > 0L) updatedAt.toString() else "",
            )
        }
    }
}
