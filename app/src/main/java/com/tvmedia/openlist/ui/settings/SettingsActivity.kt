package com.tvmedia.openlist.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.tvmedia.openlist.R
import com.tvmedia.openlist.data.settings.SettingsStore
import com.tvmedia.openlist.data.source.MediaSourceRegistry
import com.tvmedia.openlist.data.source.QuarkSession
import com.tvmedia.openlist.databinding.ActivitySettingsBinding
import com.tvmedia.openlist.proxy.ProxyForegroundService
import com.tvmedia.openlist.ui.auth.QuarkLoginActivity
import com.tvmedia.openlist.ui.auth.QuarkWebSession

/**
 * 设置页：夸克账号（登录状态 / 扫码登录 / 退出登录）+ 播放方式。
 *
 * Rows are plain focusable layouts (not RadioButtons) because TV focus styling is far easier to
 * control this way. 本版本只有一个数据源，所以这里没有"切换数据源"，只有账号管理。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)

        binding.quarkActionButton.setOnClickListener { onQuarkAction() }
        binding.builtinOption.setOnClickListener { select(useSystemPlayer = false) }
        binding.systemOption.setOnClickListener { select(useSystemPlayer = true) }
        render()

        // 唯一的入口按钮先拿焦点，D-Pad 一进来就能用。
        binding.quarkActionButton.post { binding.quarkActionButton.requestFocus() }
    }

    /**
     * 退出登录必须是**真的退出**。
     *
     * 只清 [QuarkSession] 的凭证是不够的：登录走的是 app 内嵌 WebView，CookieManager 里
     * 还留着夸克会话。用户再点「登录」时，登录页一打开就带着旧 cookie，轮询立刻判定"已登录"
     * 并返回 —— 于是既退不掉、也换不了账号。
     *
     * 所以这里**同时清掉 WebView 的 cookie 与 WebStorage**，让下一次登录一定是扫码页。
     */
    private fun onQuarkAction() {
        if (QuarkSession.isLoggedIn(this)) {
            QuarkWebSession.clear()
            QuarkSession.logout(this)
        } else {
            loginLauncher.launch(Intent(this, QuarkLoginActivity::class.java))
            return
        }
        render()
    }

    /**
     * 切换播放方式。
     *
     * 两个要点（都是设备实测踩出来的）：
     *
     * 1. **先同步落盘**（`commit`，见 [SettingsStore]）：这一行决定下次点击视频走哪条路，
     *    绝不能因为进程随后被回收而丢掉 —— 那会让用户看到"切换没生效，要再点一次"。
     * 2. **不动预读代理**：两种播放方式都要经过它（见 README「代理为什么必须存在」），
     *    停掉只会在下次播放时白白重建一遍。早期版本在这里 `LocalProxy.stop()`，
     *    那是"内置播放器不走代理"时代的遗留。
     *    只撤掉**前台保活**：它存在的唯一理由是"外部播放器在前台、本 app 在后台"，
     *    切回内置播放器后本 app 就在前台，留着只会多一条"正在为外部播放器转发数据"的通知。
     */
    private fun select(useSystemPlayer: Boolean) {
        settings.useSystemPlayer = useSystemPlayer
        if (!useSystemPlayer) ProxyForegroundService.stop(this)
        render()
    }

    private fun render() {
        val useSystemPlayer = settings.useSystemPlayer
        val loggedIn = QuarkSession.isLoggedIn(this)

        binding.quarkStatusText.text = getString(
            if (loggedIn) R.string.settings_source_status_logged_in
            else R.string.settings_source_status_logged_out,
        )
        binding.quarkActionButton.text = getString(
            if (loggedIn) R.string.settings_source_logout else R.string.settings_source_login,
        )

        val notices = mutableListOf<String>()
        if (loggedIn && MediaSourceRegistry.current(this) == null) {
            // 有凭证但源构造不出来：让用户看到原因，而不是回主界面面对一个空列表。
            notices += getString(R.string.settings_source_quark_unavailable)
        }
        if (!QuarkSession.tokenStore(this).isEncrypted) {
            // 凭证加密不可用时必须明确告知，不能悄悄降级。
            notices += getString(R.string.settings_source_plain_storage_warning)
        }
        binding.quarkNoticeText.text = notices.joinToString("\n")
        binding.quarkNoticeText.visibility = if (notices.isEmpty()) View.GONE else View.VISIBLE

        bind(binding.builtinMark, binding.builtinTitle, binding.builtinDesc, selected = !useSystemPlayer)
        bind(binding.systemMark, binding.systemTitle, binding.systemDesc, selected = useSystemPlayer)
    }

    private fun bind(mark: TextView, title: TextView, desc: TextView, selected: Boolean) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        mark.text = getString(
            if (selected) R.string.mark_selected else R.string.mark_unselected,
        )
        mark.setTextColor(
            if (selected) accent else ContextCompat.getColor(this, R.color.text_secondary),
        )
        title.setTextColor(
            if (selected) accent else ContextCompat.getColor(this, R.color.text_primary),
        )
        desc.alpha = if (selected) 1f else 0.6f
    }
}
