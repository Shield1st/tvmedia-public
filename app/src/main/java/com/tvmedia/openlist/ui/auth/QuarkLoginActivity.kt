package com.tvmedia.openlist.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import com.tvmedia.openlist.log.AppLog
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import com.tvmedia.openlist.Config
import com.tvmedia.openlist.R
import com.tvmedia.openlist.data.source.QuarkSession
import com.tvmedia.openlist.databinding.ActivityQuarkLoginBinding
import com.tvmedia.openlist.quark.QuarkCookies
import com.tvmedia.openlist.quark.QuarkProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 夸克登录页（**WebView 版**）。
 *
 * ```
 * WebView 打开 pan.quark.cn（普通浏览器 UA）→ 自动点开「登录账号」弹窗
 *   → 用户用手机夸克 App 扫二维码 → 从 CookieManager 抓 cookie
 *   → 用一次最小列目录探活 → 存盘 → 返回
 * ```
 *
 * 这样拿到的是**网页版会话**：满速、不占「夸克 TV 设备」名额（TV 端那条路每次扫码都会
 * 新增一个设备，上限 2 个），也不需要任何电脑。
 *
 * ### 三个踩过的坑（都在这里修掉了）
 *
 * 1. **UA 绝不能用桌面客户端 UA**。夸克网页靠
 *    `navigator.userAgent.indexOf("Electron") >= 0` 判断"我是不是夸克的桌面客户端"。
 *    照抄 OpenList 的客户端 UA（含 `Electron/18.3.5.4`）会让页面走 Electron 分支，
 *    点「登录账号」去调只存在于夸克自己 Electron 壳里的 `chrome.account.openLoginWindow`，
 *    在 WebView 里就是**点了没反应、页面像卡住**。
 *    所以这里用普通桌面浏览器 UA（[Config.USER_AGENT]）；
 *    调 API 时仍用客户端 UA —— OpenList 也是拿浏览器 cookie 配客户端 UA 用的。
 * 2. **WebView 默认不处理 `window.open`**：登录弹窗若是新窗口，同样表现成"点了没反应"。
 *    所以打开多窗口并把弹窗内容加载回同一个 WebView。
 * 3. **网页里的焦点没有可见样式**：遥控器挪焦点时完全看不出落在哪。
 *    所以注入一段强制 `:focus` 描边的 CSS。
 *
 * 另外：登录二维码藏在一个弹窗里（要先把弹窗点开），而遥控器在网页里挪焦点很痛苦，
 * 所以页面加载后会**尽力自动点一次**「登录账号」；找不到就什么都不做，
 * 用户仍可按「显示登录二维码」按钮重试或手动操作。
 */
class QuarkLoginActivity : ComponentActivity() {

    private lateinit var binding: ActivityQuarkLoginBinding
    private var pollJob: Job? = null

    /** 防止「页面加载完成」与轮询同时触发验证。 */
    private var verifying = false

    /** 页面状态。用来决定还要不要继续尝试自动点开登录弹窗。 */
    private var stage: Stage = Stage.LOADING

    /** 自动点开登录弹窗的已尝试次数（SPA 渲染慢，需要补几次，但不能无限点）。 */
    private var autoClickAttempts = 0

    /** `window.open` 出来的临时 WebView，用完要销毁。 */
    private var popupWebView: WebView? = null

    /** 上一次打过日志的 cookie 形状，避免刷屏。 */
    private var lastLoggedCookieShape: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 少数电视盒子没有 WebView provider（或版本太旧），**布局 inflation 本身就会抛**。
        // 这是设备问题，不是崩溃理由 —— 给一句能看懂的提示然后结束。
        binding = try {
            ActivityQuarkLoginBinding.inflate(layoutInflater)
        } catch (e: Throwable) {
            AppLog.w(TAG, "webview unavailable on this device", e)
            Toast.makeText(this, R.string.quark_login_webview_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(binding.root)

        binding.reloadButton.setOnClickListener { reloadLoginPage() }
        binding.showLoginButton.setOnClickListener { openLoginDialog() }
        onBackPressedDispatcher.addCallback(this) { finish() }

        setUpWebView()
        render()
        reloadLoginPage()
        startPolling()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        popupWebView?.destroy()
        popupWebView = null
        binding.webView.destroy()
        super.onDestroy()
    }

    /**
     * 登录页必须跑 JS（扫码、轮询登录状态都在页面里）。
     *
     * 只加载夸克官方域名、不注入任何脚本，所以这个警告在这里是有意接受的：
     * 关掉 JS 的话登录页根本无法工作。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        val webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 关键：普通桌面浏览器 UA。带上 "Electron" 会让页面走桌面客户端分支，
            // 那条分支依赖夸克自己的壳，在 WebView 里必然点不动（见类 KDoc）。
            userAgentString = Config.USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            // 登录弹窗可能是新窗口，必须允许并接管，否则点了没反应。
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // 把弹窗内容直接加载回主 WebView，而不是丢给一个我们看不见的新窗口。
                val popup = WebView(this@QuarkLoginActivity)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (url != ABOUT_BLANK) binding.webView.loadUrl(url)
                    }
                }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
                resultMsg.sendToTarget()
                popupWebView?.destroy()
                popupWebView = popup
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                AppLog.i(TAG, "page finished: $url")
                installFocusStyles()
                stage = Stage.WAITING
                autoClickAttempts = 0
                render()
                openLoginDialog()
                lifecycleScope.launch { tryCompleteLogin() }
            }
        }
    }

    private fun render() {
        binding.statusText.setText(
            when (stage) {
                Stage.LOADING -> R.string.quark_login_status_loading
                Stage.WAITING -> R.string.quark_login_status_waiting
                Stage.VERIFYING -> R.string.quark_login_status_verifying
            },
        )
    }

    private fun reloadLoginPage() {
        binding.webView.loadUrl(QuarkProtocol.LOGIN_URL)
    }

    /**
     * 尽力把「登录账号」弹窗点开 —— 二维码在里面。
     *
     * 找不到就什么都不做（页面照常可用），所以这个注入是安全的；
     * 之所以要自动点，是因为遥控器在网页里挪焦点既慢又看不见。
     */
    private fun openLoginDialog() {
        binding.webView.evaluateJavascript(AUTO_CLICK_LOGIN_JS) { result ->
            // 打出来是为了万一还是点不开时能直接定位：not-found 说明没找到登录按钮，
            // clicked 说明点到了（那问题就在别处）。
            AppLog.i(TAG, "auto-click login dialog -> $result")
        }
    }

    /** 网页里 `:focus` 没有可见样式，遥控器挪焦点完全看不出落在哪 —— 强制加描边。 */
    private fun installFocusStyles() {
        binding.webView.evaluateJavascript(FOCUS_STYLE_JS, null)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // SPA 渲染慢，弹窗可能晚于 onPageFinished 出现 —— 补点几次就停，
                // 免得反复点同一个按钮反而把弹窗关掉。
                if (stage == Stage.WAITING && autoClickAttempts < MAX_AUTO_CLICKS) {
                    autoClickAttempts++
                    openLoginDialog()
                }
                tryCompleteLogin()
            }
        }
    }

    /**
     * 抓 cookie → 探活 → 存盘 → 返回。
     *
     * 探活失败（用户还没真正登录完 / cookie 无效）时**不报错、不退出**，继续等：
     * 扫码过程中 cookie 会先出现、后生效，这时报错只会干扰用户。
     */
    private suspend fun tryCompleteLogin() {
        if (verifying) return
        val cookie = readCookies() ?: return
        if (!QuarkCookies.hasSession(cookie)) return

        verifying = true
        stage = Stage.VERIFYING
        render()

        val store = QuarkSession.tokenStore(this)
        val previous = store.cookie
        store.cookie = cookie
        try {
            QuarkSession.apiClient(this).verifySession()
            QuarkSession.onCookieChanged()
            Toast.makeText(this, R.string.quark_login_success, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 还原临时写入的 cookie：它还没被证明可用，不该留在盘上。
            store.cookie = previous
            verifying = false
            AppLog.w(TAG, "captured a cookie but it is not usable yet: ${e.message}")
            stage = Stage.WAITING
            render()
        }
    }

    /**
     * 从 WebView 的 CookieManager 里取 cookie。
     *
     * 网页与 API 是两个 host（`pan.quark.cn` / `drive.quark.cn`），各取一次再合并 ——
     * 只取一个可能漏掉认证字段。
     */
    private fun readCookies(): String? {
        val manager = CookieManager.getInstance()
        val web = manager.getCookie(QuarkProtocol.COOKIE_HOST_WEB).orEmpty()
        val api = manager.getCookie(QuarkProtocol.COOKIE_HOST_API).orEmpty()
        val merged = QuarkCookies.merge(web, api)
        if (merged != lastLoggedCookieShape) {
            // 只打形状（有哪些 cookie 名），不打值 —— cookie 是凭证。
            lastLoggedCookieShape = merged
            AppLog.i(TAG, "cookies: web=${web.isNotBlank()} api=${api.isNotBlank()} " +
                "hasSession=${QuarkCookies.hasSession(merged)} names=${cookieNames(merged)}")
        }
        return merged.takeIf { it.isNotBlank() }
    }

    /** 仅用于日志：列出 cookie 名（不包含值）。 */
    private fun cookieNames(cookie: String): List<String> =
        cookie.split(';').mapNotNull {
            it.trim().substringBefore('=').takeIf { name -> name.isNotEmpty() }
        }

    private enum class Stage { LOADING, WAITING, VERIFYING }

    private companion object {
        const val TAG = "QuarkLoginActivity"
        const val ABOUT_BLANK = "about:blank"

        /** 兜底轮询：WebView 的 onPageFinished 不一定会在「扫码确认」这种页面内变化时触发。 */
        const val POLL_INTERVAL_MS = 1_500L

        /** 自动点开登录弹窗的补点次数上限（之后交给用户按按钮）。 */
        const val MAX_AUTO_CLICKS = 4

        /**
         * 找「登录账号」之类的可见元素并点它。
         *
         * 刻意做成"尽力而为"：找不到就返回 not-found，不抛、不改动页面。
         */
        const val AUTO_CLICK_LOGIN_JS = """
            (function () {
              try {
                var wanted = ['登录账号', '立即登录', '登录/注册', '登录'];
                var nodes = document.querySelectorAll('a,button,div,span');
                for (var w = 0; w < wanted.length; w++) {
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if ((el.textContent || '').trim() !== wanted[w]) continue;
                    if (el.offsetParent === null) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width < 8 || r.height < 8) continue;
                    el.click();
                    return 'clicked';
                  }
                }
                return 'not-found';
              } catch (e) { return 'error'; }
            })();
        """

        /** 强制给焦点元素加可见描边（夸克页面的 `:focus` 没有样式）。 */
        const val FOCUS_STYLE_JS = """
            (function () {
              try {
                if (document.getElementById('tvmedia-focus-style')) return;
                var s = document.createElement('style');
                s.id = 'tvmedia-focus-style';
                s.innerHTML = '*:focus{outline:3px solid #42A5F5 !important;' +
                              'outline-offset:2px !important;background-color:rgba(66,165,245,0.18) !important;}';
                document.head.appendChild(s);
              } catch (e) {}
            })();
        """
    }
}
