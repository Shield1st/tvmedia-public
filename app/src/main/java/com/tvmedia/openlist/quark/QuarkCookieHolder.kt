package com.tvmedia.openlist.quark

/**
 * 夸克 cookie 的内存视图。
 *
 * 实现方（`data/settings/TokenStore`）负责真正的存储策略：
 *
 * - cookie **加密持久化**（`EncryptedSharedPreferences`）—— 它就是网页版会话的凭证；
 * - **不得**进日志或异常消息；
 * - 服务端每次响应可能下发新的 `__puus`，[QuarkApiClient] 会把它合并回来并写盘
 *   （这就是 cookie 的自动续期，参考实现也是这么做的）。
 */
interface QuarkCookieHolder {

    /** 整串 cookie（`name=value; name=value; …`）；为空表示未登录。 */
    var cookie: String
}
