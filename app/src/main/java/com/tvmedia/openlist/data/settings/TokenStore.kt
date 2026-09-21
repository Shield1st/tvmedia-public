package com.tvmedia.openlist.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.tvmedia.openlist.log.AppLog
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.tvmedia.openlist.quark.QuarkCookieHolder
import com.tvmedia.openlist.quark.QuarkCookies

/**
 * 夸克凭证存储。
 *
 * 凭证就是**网页版的整串 cookie**（`name=value; …`）—— 它不是「设备」，所以不像 TV 端那样
 * 每次登录都占用一个夸克 TV 设备名额。
 *
 * - cookie **加密持久化**（`EncryptedSharedPreferences`）；
 * - 服务端每次响应可能下发新的 `__puus`，[com.tvmedia.openlist.quark.QuarkApiClient]
 *   会合并回来并写盘 —— 这就是会话自动续期；
 * - 加密不可用时（Keystore 异常、密钥失效）**降级为普通 prefs**，并把 [isEncrypted] 置 false
 *   交给设置页明确告知。绝不因为存储层异常让 app 崩溃。
 */
class TokenStore(context: Context) : QuarkCookieHolder {

    private val prefs: SharedPreferences

    /** 加密是否真正生效。false 表示已降级为普通 prefs。 */
    val isEncrypted: Boolean

    init {
        val resolved = createPreferences(context.applicationContext)
        prefs = resolved.preferences
        isEncrypted = resolved.encrypted
    }

    /** 整串 cookie；空值表示未登录，必须走登录页。 */
    override var cookie: String
        get() = prefs.getString(KEY_COOKIE, null).orEmpty()
        set(value) = prefs.edit {
            if (value.isBlank()) remove(KEY_COOKIE) else putString(KEY_COOKIE, value)
        }

    /** 是否持有看起来可用的会话（真正的有效性由 [com.tvmedia.openlist.quark.QuarkApiClient.verifySession] 判定）。 */
    val isLoggedIn: Boolean get() = QuarkCookies.hasSession(cookie)

    /** 退出登录：彻底清除凭证。 */
    fun logout() {
        prefs.edit { remove(KEY_COOKIE) }
    }

    private data class ResolvedPreferences(
        val preferences: SharedPreferences,
        val encrypted: Boolean,
    )

    /**
     * 1.0.0 是 `security-crypto` 唯一的稳定版，它用 [MasterKeys]；
     * `MasterKey` 是 1.1.0-alpha 才有的新 API。这里不为了消一个弃用警告而改用 alpha 版。
     */
    @Suppress("DEPRECATION")
    private fun createPreferences(context: Context): ResolvedPreferences =
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            ResolvedPreferences(
                EncryptedSharedPreferences.create(
                    // 注意：1.0.0 的参数顺序是 (fileName, masterKeyAlias, context, …)，
                    // 1.1.0 才改成 (context, fileName, masterKey, …)。
                    ENCRYPTED_PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ),
                encrypted = true,
            )
        } catch (e: Exception) {
            // 已知会发生在：设备 Keystore 不可用、系统升级后密钥失效、备份恢复后密钥丢失。
            // 降级后功能仍然可用（cookie 明文存本地），但要由设置页告知用户。
            AppLog.w(TAG, "encrypted prefs unavailable, degrading to plain prefs", e)
            ResolvedPreferences(
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE),
                encrypted = false,
            )
        }

    private companion object {
        const val TAG = "TokenStore"
        const val ENCRYPTED_PREFS_NAME = "tvmedia_quark_secure"
        const val FALLBACK_PREFS_NAME = "tvmedia_quark_plain"
        const val KEY_COOKIE = "cookie"
    }
}
