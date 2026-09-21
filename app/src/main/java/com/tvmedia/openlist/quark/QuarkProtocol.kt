package com.tvmedia.openlist.quark

/**
 * 夸克**网页版 / 桌面客户端**协议的常量。
 *
 * **协议来源**：OpenList v4.2.2 `drivers/quark_uc/{meta.go,util.go}`（驱动名 `Quark`）。
 *
 * 它与同仓库的 `quark_uc_tv` 是**两套不同的 API**，不要混用：
 *
 * | | 本实现（网页版 `quark_uc`） | TV 端 `quark_uc_tv`（已废弃） |
 * |---|---|---|
 * | 域名 | `drive.quark.cn/1/clouddrive` | `open-api-drive.quark.cn` |
 * | 认证 | **Cookie** | `refresh_token` + `x-pan-*` 签名 |
 * | 列目录 | `GET /file/sort` | `GET /file?method=list` |
 * | 取直链 | `POST /file/download` | `GET /file?method=download` |
 * | 直链请求头 | **Cookie + Referer + UA** | 无 |
 *
 * **为什么是网页版而不是 TV 端**（实测教训）：TV 端登录会占用「夸克 TV 设备名额」
 * （上限 2 个，每次扫码新增一个，得去管理后台清理），且播放被限速。
 * 网页版满速、不占设备名额 —— 这也是 OpenList 一直在用的那条路。
 */
object QuarkProtocol {

    /** 主 API。注意路径里已经含 `/1/clouddrive`。 */
    const val API_BASE: String = "https://drive.quark.cn/1/clouddrive"

    /** 网页版要求的 Referer。**取直链时也必须带**，否则 CDN 会拒绝。 */
    const val REFERER: String = "https://pan.quark.cn"

    /**
     * 桌面客户端 UA，照搬参考实现。
     *
     * **不能**换成 TV 端 UA 或普通浏览器 UA：网页版接口按它判定客户端类型。
     * WebView 登录时也用同一个 UA，让「登录用的身份」和「调 API 的身份」保持一致。
     */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
            "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

    /** 公共 query 参数，照搬参考实现。 */
    const val QUERY_PR: String = "ucpro"
    const val QUERY_FR: String = "pc"

    /** 登录页。WebView 打开它，用户扫码登录后从中抓 cookie。 */
    const val LOGIN_URL: String = "https://pan.quark.cn/"

    /** cookie 的域名（WebView 抓取时按这两个 host 各取一次再合并）。 */
    const val COOKIE_HOST_WEB: String = "https://pan.quark.cn"
    const val COOKIE_HOST_API: String = "https://drive.quark.cn"

    /**
     * 会话 cookie 名。
     *
     * 参考实现只依赖用户粘贴的整串 cookie，没有单独取某个字段；这里用它作为
     * 「是否已登录」的判据 —— 它是夸克的会话令牌，登录成功后才会出现。
     */
    const val COOKIE_SESSION: String = "__puus"
}
