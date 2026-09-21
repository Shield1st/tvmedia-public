package com.tvmedia.openlist.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvmedia.openlist.R
import com.tvmedia.openlist.data.model.Entry
import com.tvmedia.openlist.data.settings.SettingsStore
import com.tvmedia.openlist.data.source.MediaSource
import com.tvmedia.openlist.data.source.MediaSourceRegistry
import com.tvmedia.openlist.databinding.ActivityMainBinding
import com.tvmedia.openlist.log.AppLog
import com.tvmedia.openlist.proxy.LocalProxy
import com.tvmedia.openlist.proxy.ProxyForegroundService
import com.tvmedia.openlist.ui.auth.QuarkLoginActivity
import com.tvmedia.openlist.ui.player.PlayerActivity
import com.tvmedia.openlist.ui.settings.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Directory browser. D-Pad drives focus, BACK walks up one level and exits at root.
 *
 * 本版本只有一个数据源（夸克网盘），所以这里的职责收得很窄：
 *
 * - **有源**（已扫码登录）→ 正常浏览；
 * - **没源**（未登录 / 凭证不可用）→ 引导扫码，并在列表区给出可读提示而不是空白页。
 *
 * 源本身是进程级单例，UI 只持有 [MediaSource] 接口，不认识夸克协议。
 *
 * ### 回到列表时的位置与焦点
 *
 * 播放（或从系统播放器返回）之后**不能把用户丢回根目录**，否则想看下一集就得重新点进来。
 * 所以这里持久化两件事：
 *
 * - [SettingsStore.lastBrowsePath]：上次浏览的目录（跨进程重启）；
 * - [SettingsStore.lastPlayedPath]：上次播放的条目，回到列表时**焦点落在它上面**，
 *   用户按一次「下」就是下一集。
 *
 * 恢复出来的目录若已不存在（改名 / 删除 / 换账号），[load] 会**逐级向上回退**，最坏回到根目录。
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: EntryAdapter

    /** 当前源。未登录时为 null —— 所有使用点都必须先判空。 */
    private var source: MediaSource? = null

    private lateinit var settings: SettingsStore
    private var currentPath: String = BrowsePath.ROOT
    private var loadJob: Job? = null
    private var playJob: Job? = null
    private var spinnerJob: Job? = null

    /** 上次交给播放器的条目路径；回到列表时焦点落在它上面。 */
    private var lastPlayedPath: String? = null

    /** 冷启动只引导一次扫码：用户取消后若每次 onResume 都再拉一次，就成了退不出去的循环。 */
    private var loginPrompted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = EntryAdapter(::onEntryClicked)
        settings = SettingsStore(this)
        source = MediaSourceRegistry.current(this)

        binding.entryList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            // 整页替换时不要逐项增删动画：那样会新旧内容交叠着动，观感很乱。
            // 切换的过渡交给下面 showContent() 的整页淡入。
            itemAnimator = null
        }
        binding.retryButton.setOnClickListener { onRetry() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this) {
            val parent = BrowsePath.parentOf(currentPath)
            if (parent == null) {
                finish()
            } else {
                load(parent)
            }
        }

        // 恢复优先级：Activity 重建时带的 Bundle（最准）→ 上次浏览的目录 → 根目录。
        lastPlayedPath = savedInstanceState?.getString(STATE_PLAYED)
            ?: settings.lastPlayedPath.takeIf { it.isNotBlank() }
        val startPath = savedInstanceState?.getString(STATE_PATH)
            ?: settings.lastBrowsePath.takeIf { it.isNotBlank() }
            ?: BrowsePath.ROOT
        load(startPath, fallbackToParent = startPath != BrowsePath.ROOT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, currentPath)
        lastPlayedPath?.let { outState.putString(STATE_PLAYED, it) }
    }

    override fun onResume() {
        super.onResume()

        val current = MediaSourceRegistry.current(this)
        if (current == null) {
            // 还没有可用的源：引导扫码（只一次），列表区保持"请先登录"的提示。
            promptLoginIfNeeded()
            return
        }

        if (source !== current) {
            // 刚登录成功（从扫码页回来），或退出登录后换了账号 —— 源被重建成了新实例。
            //
            // **不要在这里把浏览位置清掉**：那会把用户丢回根目录（早期版本就是这么写的），
            // 而且冷启动时源构造偶发失败过一次也会触发这条分支。
            // 直接按原路径再列一次即可：路径在新账号下不存在时，load() 会逐级向上回退。
            AppLog.i(TAG, "source changed; reloading ${displayPath(currentPath)}")
            source = current
            load(currentPath, fallbackToParent = true)
            return
        }

        // 回到前台（从播放器或设置页返回）：收起临时的"准备中"转圈，并确保列表还有焦点。
        //
        // **这里刻意不重新定位焦点**：列表本来就会保留离开时的焦点与滚动位置，
        // 而"定位到刚播的那一条"只在内容真的重新加载时才做（见 applyFocus 的调用点）。
        // 早期版本在这里无条件 applyFocus()，结果从设置页返回时列表会被拉回顶部。
        if (binding.entryList.visibility == View.VISIBLE) {
            hideSpinner()
            if (binding.entryList.findFocus() == null) binding.entryList.requestFocus()
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        playJob?.cancel()
        spinnerJob?.cancel()
        super.onDestroy()
    }

    private fun promptLoginIfNeeded() {
        if (loginPrompted) return
        loginPrompted = true
        startActivity(Intent(this, QuarkLoginActivity::class.java))
    }

    /**
     * 重试按钮有两种语义：
     *
     * - 有源（网络/协议错误）→ 重新解析源并重载当前目录；
     * - 没源（未登录）→ 直接去扫码，而不是傻傻地重试一个不存在的源。
     */
    private fun onRetry() {
        if (MediaSourceRegistry.current(this) == null) {
            startActivity(Intent(this, QuarkLoginActivity::class.java))
            return
        }
        source = MediaSourceRegistry.current(this)
        load(currentPath)
    }

    private fun onEntryClicked(entry: Entry) {
        when {
            entry.isDir -> load(entry.path)
            entry.isPlayable -> play(entry)
        }
    }

    /**
     * 播放前必须向数据源要一次直链 —— 夸克的直链是带时效的签名 CDN 链接，由服务端下发，
     * 客户端不拼接。拿到之后**两种播放方式都改写成回环代理地址**再交出去
     * （认证已经体现在 URL 里，外部播放器不需要任何账号配置）。
     */
    private fun play(entry: Entry) {
        val current = source ?: return
        playJob?.cancel()

        // 先记住这一条：无论播放方式如何、进程是否被杀，回到列表时焦点都要落在它上面。
        lastPlayedPath = entry.path
        settings.lastPlayedPath = entry.path

        if (binding.entryList.visibility == View.VISIBLE) {
            showSpinnerNow()
        }
        playJob = lifecycleScope.launch {
            try {
                val url = current.resolvePlayUrl(entry.path)
                val headers = current.playbackHeaders()
                AppLog.i(
                    TAG,
                    "play ${entry.path} size=${entry.size} " +
                        "mode=${if (settings.useSystemPlayer) "system" else "builtin"}",
                )
                AppLog.i(TAG, "resolved upstream: ${describeUrl(url)}")

                // **两种播放方式都走本机代理**，依据是实测（`research/upstream-measurements.md`）：
                //
                // 1. **直连不可行**：夸克 CDN 对**裸 GET** 回 `412`（必须带 Cookie/Referer/UA），
                //    外部播放器没有这些头；而大请求（open-ended / 无 Range）会被限速到
                //    ~0.1 MiB/s —— 播放器默认发的 `bytes=N-` 恰好落在慢区。
                // 2. **代理把大请求拆成有界 8 MiB**（实测 13 MiB/s，3 路并发 23 MiB/s），
                //    并且对播放器**只说真话**：open-ended 的 `Content-Length` 是**剩余全长**、
                //    数据边收边发（对齐 OpenList 的 `ServeHTTP`）。早期版本在这里只报一块 8 MiB，
                //    不读 `Content-Range` 的播放器会以为整个文件只有 8 MiB —— 播几秒就"到末尾"退出。
                val port = LocalProxy.ensureStarted(
                    this@MainActivity,
                    // 外部播放器在前台时本 app 在后台、进程会被回收 —— 用前台服务把代理钉住。
                    // 内置播放器时本 app 就在前台，不需要（免得平白多一条通知）。
                    foregroundService = settings.useSystemPlayer,
                )
                val localUrl = port?.let { LocalProxy.toLocalUrl(url, it) }?.takeIf { it != url }
                AppLog.i(TAG, "proxy port=$port useProxy=${localUrl != null}")

                if (settings.useSystemPlayer) {
                    // 代理起不来时只能回退直连：外部播放器没有请求头、多半会被 CDN 拒，
                    // 但比什么都不做好（spec 里的约定）。
                    val target = localUrl ?: url
                    AppLog.i(
                        TAG,
                        "system player -> ${describeUrl(target)} (proxy=${localUrl != null})",
                    )
                    launchExternalPlayer(target, entry.name, headers)
                    return@launch
                }

                if (localUrl != null) {
                    startActivity(PlayerActivity.intent(this@MainActivity, localUrl, entry.name))
                } else {
                    // 代理起不来也不至于完全不能播：内置播放器自己带请求头。
                    startActivity(PlayerActivity.intent(this@MainActivity, url, entry.name, headers))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * 把**已经改写好**的播放地址交给系统播放器（`ACTION_VIEW`，MIME 用 `video/…` 通配）。
     *
     * 地址是回环代理地址（见 [play]）：外部播放器无从得知 CDN 要求的 Cookie/Referer/UA，
     * 而代理同时替它挡掉了 CDN 对"大请求"的限速。
     *
     * 注意：**这里绝不能再调 [LocalProxy.toLocalUrl]**。
     * 早期版本在这里又包了一层，而调用方（[play]）已经包过 —— 于是播放器拿到的是
     * "代理套代理"的地址，代理去请求自己 → 502 → 外部播放器报「此片源无法播放」。
     * 内置播放器走的是另一条分支，所以只有外部播放器受害。
     */
    private fun launchExternalPlayer(url: String, title: String, headers: Map<String, String>) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra(Intent.EXTRA_TITLE, title)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_external_player, Toast.LENGTH_LONG).show()
            // 设备上没有外部播放器（例如刚装好、什么都没装）时本 app 仍在前台，
            // 前台保活和那条"正在为外部播放器转发数据"的通知就没有意义了 —— 撤掉。
            // 预读代理本身不动：内置播放器照样要经过它。
            ProxyForegroundService.stop(this)
            startActivity(PlayerActivity.intent(this, url, title, headers))
        }
    }

    /**
     * 列出 [path]。
     *
     * [fallbackToParent] 只用于「恢复上次位置」的首次加载：那个目录可能已经被改名/删除，
     * 逐级向上回退比直接甩一个错误页合理。
     */
    private fun load(path: String, fallbackToParent: Boolean = false) {
        loadJob?.cancel()
        spinnerJob?.cancel()
        hideSpinner()

        currentPath = path
        binding.pathText.text = displayPath(path)
        binding.errorGroup.visibility = View.GONE

        val current = source
        if (current == null) {
            // 未登录时不要留一个空白页：给出明确提示，并把焦点交给重试按钮（它会去扫码）。
            binding.entryList.visibility = View.INVISIBLE
            binding.emptyText.visibility = View.GONE
            showError(getString(R.string.main_needs_quark_login))
            return
        }
        settings.lastBrowsePath = path

        // 立刻收起旧内容：ListAdapter 的 diff 是异步算的，等它算完再切换的话，
        // 中间那一瞬列表里还是**上一级目录**的内容（用户看到的"闪一下根目录"就是这个）。
        binding.entryList.visibility = View.INVISIBLE
        binding.emptyText.visibility = View.GONE

        // 转圈延迟出现：本地/局域网很快时不该闪一下 loading。
        spinnerJob = lifecycleScope.launch {
            delay(SPINNER_DELAY_MS)
            showSpinnerNow()
        }

        loadJob = lifecycleScope.launch {
            try {
                val entries = current.list(path)
                spinnerJob?.cancel()
                showContent(path, entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancel()
                val parent = if (fallbackToParent) BrowsePath.parentOf(path) else null
                if (parent != null) {
                    AppLog.i(TAG, "restore fell back: ${displayPath(path)} -> ${displayPath(parent)}")
                    load(parent, fallbackToParent = true)
                } else {
                    showError(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * 显示新目录内容。
     *
     * **必须等 `submitList` 的 diff commit 完成再显示**：提交是异步的，提前把列表设成可见
     * 会让旧内容（上一级目录）短暂露出来 —— 这就是"进入下一级目录时先看到根目录"的根因。
     */
    private fun showContent(path: String, entries: List<Entry>) {
        adapter.submitList(entries) {
            // 快速连点目录时，上一次的 commit 回调可能在新的一次 load 之后才跑 ——
            // 用它所属的 path 做闸门，避免把已经被替换掉的目录内容又显示出来。
            if (isFinishing || path != currentPath) return@submitList
            hideSpinner()
            binding.errorGroup.visibility = View.GONE
            binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            revealList()
            applyFocus()
        }
    }

    /** 立刻显示转圈（带淡入）。 */
    private fun showSpinnerNow() {
        binding.progressBar.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(SPINNER_FADE_MS).start()
        }
    }

    /** 收起转圈，并把 alpha 复位（否则下一次淡入会从中途的 alpha 开始）。 */
    private fun hideSpinner() {
        binding.progressBar.apply {
            animate().cancel()
            alpha = 1f
            visibility = View.GONE
        }
    }

    /** 淡入显示列表：整页替换没有逐项动画，靠这一次淡入提供过渡。 */
    private fun revealList() {
        binding.entryList.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(LIST_FADE_MS).start()
        }
    }

    /**
     * 把 D-Pad 焦点放到「刚播的那个文件」上（不在当前目录时退化为第一项）。
     *
     * 用户按一次「下」就是下一集，不用再重新找位置 —— 这是回到列表后最常用的动作。
     */
    private fun applyFocus() {
        val played = lastPlayedPath
        val index = if (played.isNullOrBlank()) {
            -1
        } else {
            adapter.currentList.indexOfFirst { it.path == played }
        }
        focusPosition(if (index >= 0) index else 0)
    }

    /**
     * 滚动到 [position] 并让它拿到焦点。
     *
     * 视图是异步布局的，第一帧里目标项可能还没被创建，所以这里最多重试 [FOCUS_RETRIES] 次，
     * 仍然拿不到就退化为「列表本身拿到焦点」（至少方向键可用）。
     */
    private fun focusPosition(position: Int, attempt: Int = 0) {
        binding.entryList.post {
            if (isFinishing) return@post
            val layoutManager = binding.entryList.layoutManager as? LinearLayoutManager ?: return@post
            if (position >= adapter.itemCount) {
                binding.entryList.requestFocus()
                return@post
            }
            layoutManager.scrollToPosition(position)
            binding.entryList.post {
                if (isFinishing) return@post
                val child = layoutManager.findViewByPosition(position)
                when {
                    child != null -> child.requestFocus()
                    attempt < FOCUS_RETRIES -> focusPosition(position, attempt + 1)
                    else -> binding.entryList.requestFocus()
                }
            }
        }
    }

    private fun showError(message: String) {
        hideSpinner()
        binding.entryList.visibility = View.INVISIBLE
        binding.emptyText.visibility = View.GONE
        binding.errorGroup.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.retryButton.post { binding.retryButton.requestFocus() }
    }

    private fun displayPath(path: String): String =
        if (path == BrowsePath.ROOT) getString(R.string.main_root) else path

    /** 只打主机与路径，**不打 query** —— 夸克直链的签名就在 query 里，那是凭证。 */
    private fun describeUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url.take(80)
        val pathStart = url.indexOf('/', schemeEnd + 3)
        if (pathStart < 0) return url.take(80)
        return url.substring(0, pathStart) + url.substring(pathStart).substringBefore('?')
    }

    private companion object {
        const val TAG = "MainActivity"
        const val STATE_PATH = "state_path"
        const val STATE_PLAYED = "state_played"

        /** 加载超过这个时间才显示转圈，避免快速切换时闪一下。 */
        const val SPINNER_DELAY_MS = 220L
        const val SPINNER_FADE_MS = 150L

        /** 整页切换的淡入时长：够短，不会拖慢操作；够长，不像硬切。 */
        const val LIST_FADE_MS = 180L

        const val FOCUS_RETRIES = 2
    }
}
