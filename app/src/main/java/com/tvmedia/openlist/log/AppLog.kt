package com.tvmedia.openlist.log

import android.util.Log

/**
 * 应用日志门面：**只进 Logcat**。
 *
 * 早期版本另有一个「把日志写进存储」的排查开关（目标是给开不了 adb 的电视盒子用）。
 * 面向用户的版本已经**整体移除**：调试用 `adb logcat` 或模拟器就够了，
 * 让 app 往用户存储里写 `logs/` 只会打扰正常使用。
 *
 * 约定：
 * - **凭证不进日志** —— cookie / token 的值绝不能传给这里（调用方负责）；
 * - 只做 Logcat 输出，不持有任何状态、不写盘。
 */
object AppLog {

    fun d(tag: String, message: String) = Log.d(tag, message)

    fun i(tag: String, message: String) = Log.i(tag, message)

    fun w(tag: String, message: String) = Log.w(tag, message)

    fun w(tag: String, message: String, error: Throwable?) =
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
}
