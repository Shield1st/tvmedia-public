package com.tvmedia.openlist.quark

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 宽容的 JSON 取值工具。
 *
 * 服务端字段缺失、为 `null`、或**类型发生变化**都不得导致崩溃（见 spec 的 DTO 约定），
 * 所以刻意不用 `asString` / `asInt` —— 它们对类型不符会抛
 * `UnsupportedOperationException` / `NumberFormatException`，那不是我们想要的失败方式。
 */
internal fun JsonObject.stringOrEmpty(name: String): String {
    val element = get(name)?.takeIf { !it.isJsonNull } ?: return ""
    if (!element.isJsonPrimitive) return ""
    return element.asJsonPrimitive.asString
}

/** 字段缺失 / `null` / 非数字一律返回 0；数字字符串也接受（服务端可能把数字发成字符串）。 */
internal fun JsonObject.intOrZero(name: String): Int {
    val element = get(name)?.takeIf { !it.isJsonNull } ?: return 0
    if (!element.isJsonPrimitive) return 0
    val primitive = element.asJsonPrimitive
    return when {
        primitive.isNumber -> primitive.asInt
        primitive.isString -> primitive.asString.trim().toIntOrNull() ?: 0
        else -> 0
    }
}

/** 字段缺失 / `null` / 非布尔一律返回 false。网页版用 `file: true` 表示「是文件」。 */
internal fun JsonObject.booleanOrFalse(name: String): Boolean {
    val element = get(name)?.takeIf { !it.isJsonNull } ?: return false
    if (!element.isJsonPrimitive) return false
    val primitive = element.asJsonPrimitive
    return when {
        primitive.isBoolean -> primitive.asBoolean
        primitive.isString -> primitive.asString.trim().equals("true", ignoreCase = true)
        primitive.isNumber -> primitive.asInt != 0
        else -> false
    }
}

/** 与 [intOrZero] 同理，用于 `size` / `updated_at` 这类大数值。 */
internal fun JsonObject.longOrZero(name: String): Long {
    val element = get(name)?.takeIf { !it.isJsonNull } ?: return 0L
    if (!element.isJsonPrimitive) return 0L
    val primitive = element.asJsonPrimitive
    return when {
        primitive.isNumber -> primitive.asLong
        primitive.isString -> primitive.asString.trim().toLongOrNull() ?: 0L
        else -> 0L
    }
}

/** 字段缺失 / `null` / 类型不符一律返回 null（用 `getAsJsonObject`/`getAsJsonArray` 会抛）。 */
internal fun JsonObject.jsonObjectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.jsonArrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

/** 优先回显服务端 `error_info`，其次 `message`，最后给出上下文。 */
internal fun JsonObject.errorInfo(context: String): String =
    stringOrEmpty("error_info").ifBlank {
        stringOrEmpty("message").ifBlank { "$context failed" }
    }
