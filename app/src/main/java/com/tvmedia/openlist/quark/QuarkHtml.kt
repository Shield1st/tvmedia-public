package com.tvmedia.openlist.quark

/**
 * HTML 实体反转义。**纯 JVM，可单元测试**。
 *
 * 对应参考实现里的 `html.UnescapeString(file.FileName)`：夸克网页版返回的文件名
 * 会把 `&` 之类编码成实体（例如 `A&amp;B.mkv`），不解开就会显示成 `A&amp;B.mkv`。
 *
 * 刻意不引第三方库：只需要处理实体名和数字实体两类，规则很短。
 */
internal object QuarkHtml {

    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
    )

    fun unescape(text: String): String =
        if (!text.contains('&')) {
            text
        } else {
            ENTITY.replace(text) { match ->
                decode(match.groupValues[1]) ?: match.value
            }
        }

    private fun decode(entity: String): String? = when {
        entity.startsWith("#x", ignoreCase = true) ->
            entity.drop(2).toIntOrNull(16)?.let(::fromCodePoint)
        entity.startsWith('#') ->
            entity.drop(1).toIntOrNull()?.let(::fromCodePoint)
        else -> NAMED[entity.lowercase()]
    }

    private fun fromCodePoint(code: Int): String? =
        if (code in 1..0x10FFFF) String(Character.toChars(code)) else null
}
