package com.tvmedia.openlist.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import com.tvmedia.openlist.Config
import com.tvmedia.openlist.R
import com.tvmedia.openlist.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen playback, driven by a **TV remote** rather than by touch.
 *
 * ### 为什么不用 media3 自带的控制条
 *
 * `PlayerView` 的控制器是给触摸设计的：中央有播放/暂停与 ±15 秒按钮、右下角有字幕/音轨按钮、
 * 左右键是百分比快进。电视上没有"选中某个按钮"这种操作，遥控器的左右键本身就应该是快退/快进。
 * 所以这里 `app:use_controller="false"`，自己实现一套 TV 语义：
 *
 * | 按键 | 行为 |
 * |---|---|
 * | ← / → | 快退 / 快进 10 秒；**长按**转为连续快退/快进 |
 * | ↑ / ↓ | 唤出底部信息条 |
 * | 确定 | 播放 / 暂停 |
 * | 菜单（选项） | 左侧弹出字幕 / 音轨面板 |
 * | 返回 | 信息条可见 → 只收起信息条；已收起 → 第一次提示「再按一次返回退出播放」，第二次退出 |
 *
 * 播放自然结束时自动退出播放页，配合列表页的「焦点落在刚播的文件上」，
 * 用户按一次「下」就是下一集。
 *
 * ### 保留的两个播放设置
 *
 *  1. a desktop-Chrome User-Agent on the data source, so the server does not
 *     throttle us like an unknown player;
 *  2. a deliberately large load control buffer (15s / 60s / 2.5s start).
 *
 * Subtitles are opt-in in media3: `DefaultTrackSelector` only auto-selects a text track that
 * carries the DEFAULT selection flag or caption role flags, which MKV embedded subtitles
 * normally do not. Both a language preference and an `onTracksChanged` fallback are needed.
 *
 * Most media3 APIs used here are annotated `@UnstableApi`, which is why the class
 * opts in explicitly instead of sprinkling annotations at call sites.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    /** Guards the one-shot subtitle fallback so it never overrides a later manual choice. */
    private var subtitleAutoSelected = false

    private var controlsVisible = false
    private var optionsVisible = false

    /** 「再按一次返回退出播放」的提示是否已经给出（有时效）。 */
    private var exitArmed = false

    private var progressJob: Job? = null
    private var hideBarJob: Job? = null
    private var seekRepeatJob: Job? = null
    private var seekFeedbackJob: Job? = null

    /** 左右键是否正被按住（避免把没处理过的 ACTION_UP 吞掉）。 */
    private var seekKeyHeld = false

    /** 正在显示的 seek 反馈文案；为 null 时信息条显示播放状态。 */
    private var seekFeedback: String? = null

    /** 选项面板里可聚焦的行，按显示顺序；用于左右键跨组跳转与重建后恢复焦点。 */
    private val optionRows = mutableListOf<OptionRow>()

    private val exitDisarm = Runnable { exitArmed = false }

    private data class OptionRow(val section: Int, val selected: Boolean, val view: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 按键全部由 Activity 处理：PlayerView（以及它内部已隐藏的控制条）不该抢焦点，
        // 否则方向键会先落在一个没有焦点的视图上，行为变得不可预测。
        binding.playerView.isFocusable = false
        binding.playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        hideSystemBars()
        configureSubtitles()

        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        startProgressUpdates()
    }

    override fun onStop() {
        super.onStop()
        stopSeekRepeat()
        progressJob?.cancel()
        hideBarJob?.cancel()
        seekFeedbackJob?.cancel()
        binding.root.removeCallbacks(exitDisarm)
        releasePlayer()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(exitDisarm)
        super.onDestroy()
    }

    // --- 按键（TV 遥控器语义） ------------------------------------------------

    /*
     * 用 `onKeyDown` / `onKeyUp` 而不是 `dispatchKeyEvent`：
     * androidx 把 `ComponentActivity.dispatchKeyEvent` 标成了 `@RestrictTo(LIBRARY_GROUP_PREFIX)`
     * （它要负责把返回键接到 OnBackPressedDispatcher），在应用里覆写会触发 lint 的
     * RestrictedApi 错误。而 Activity 的 `onKeyDown` / `onKeyUp` 是公开契约，没有这个问题。
     *
     * 事件顺序也正好合适：视图树（打开的面板行）先拿到方向键 —— 面板内的上下移动由框架的
     * 焦点搜索完成；只有没人消费时才会落到这里（快退/快进、播放暂停、唤出信息条）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(keyCode, event, isDown = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(keyCode, event, isDown = false) || super.onKeyUp(keyCode, event)

    private fun handleKey(keyCode: Int, event: KeyEvent, isDown: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                // 用按下事件：部分遥控器的「选项」键不一定会发抬起事件。
                if (isDown && event.repeatCount == 0) toggleOptions()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> return handleHorizontalKey(event, isDown, direction = -1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> return handleSeekKey(event, isDown, direction = -1)

            KeyEvent.KEYCODE_DPAD_RIGHT -> return handleHorizontalKey(event, isDown, direction = +1)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return handleSeekKey(event, isDown, direction = +1)

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                // 面板打开时确定键交给聚焦的那一行（选中轨道），这里不抢。
                if (optionsVisible) return false
                if (isDown) togglePlayPause()
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 面板打开时上下键属于面板的焦点移动（正常情况下根本到不了这里）。
                if (optionsVisible) return false
                if (isDown) showControls()
                return true
            }
        }
        // 其余按键（含 BACK）一律不消费 —— BACK 必须继续走 onBackPressedDispatcher。
        return false
    }

    /**
     * 左右键：面板打开时用于在「字幕 / 音轨」两组之间跳转，否则是快退/快进。
     */
    private fun handleHorizontalKey(event: KeyEvent, isDown: Boolean, direction: Int): Boolean {
        if (optionsVisible) {
            if (!isDown) moveSection(direction)
            return true
        }
        return handleSeekKey(event, isDown, direction)
    }

    /**
     * 单次点击 = 快退/快进 [SEEK_STEP_MS]；**长按**超过 [LONG_PRESS_DELAY_MS] 后转为连续 seek。
     *
     * 不用框架给的 `repeatCount` 驱动连续 seek：首次重复的延迟由系统决定且不可控，
     * 自己计时更稳（`repeatCount == 0` 只是用来判断"这一下是新按的"）。
     */
    private fun handleSeekKey(event: KeyEvent, isDown: Boolean, direction: Int): Boolean {
        if (optionsVisible) return false
        if (isDown) {
            if (event.repeatCount == 0) {
                seekKeyHeld = true
                seekBy(direction, SEEK_STEP_MS)
                startSeekRepeat(direction)
            }
            return true
        }
        if (seekKeyHeld) {
            seekKeyHeld = false
            stopSeekRepeat()
            return true
        }
        return false
    }

    private fun seekBy(direction: Int, millis: Long) {
        val exo = player ?: return
        val duration = exo.duration
        var target = exo.currentPosition + direction * millis
        if (target < 0L) target = 0L
        if (duration > 0L && target > duration) target = duration
        exo.seekTo(target)
        showSeekFeedback(direction)
    }

    private fun startSeekRepeat(direction: Int) {
        stopSeekRepeat()
        seekRepeatJob = lifecycleScope.launch {
            delay(LONG_PRESS_DELAY_MS)
            while (isActive) {
                seekBy(direction, SEEK_STEP_MS)
                delay(FAST_SEEK_INTERVAL_MS)
            }
        }
    }

    private fun stopSeekRepeat() {
        seekRepeatJob?.cancel()
        seekRepeatJob = null
    }

    private fun showSeekFeedback(direction: Int) {
        seekFeedback = getString(
            if (direction > 0) R.string.player_seek_forward else R.string.player_seek_backward,
            (SEEK_STEP_MS / 1000).toInt(),
        )
        showControls()
        seekFeedbackJob?.cancel()
        seekFeedbackJob = lifecycleScope.launch {
            delay(SEEK_FEEDBACK_MS)
            seekFeedback = null
            updateControls()
        }
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        showControls()
    }

    /**
     * 返回键两段语义：
     *
     * 1. 面板可见 → 关面板；信息条可见 → 只收起信息条（不退出）；
     * 2. 两者都不可见（自然隐藏或刚被按过一次）→ 第一次给提示，第二次才退出。
     */
    private fun handleBack() {
        when {
            optionsVisible -> hideOptions()
            controlsVisible -> hideControls()
            exitArmed -> {
                exitArmed = false
                finish()
            }

            else -> {
                exitArmed = true
                Toast.makeText(this, R.string.player_back_again_to_exit, Toast.LENGTH_SHORT).show()
                binding.root.postDelayed(exitDisarm, EXIT_ARM_TIMEOUT_MS)
            }
        }
    }

    // --- 顶部标题条 + 底部时间轴（都只读） --------------------------------------

    private fun showControls() {
        hideBarJob?.cancel()
        listOf(binding.topBar, binding.infoBar).forEach {
            it.animate().cancel()
            it.alpha = 1f
            it.visibility = View.VISIBLE
        }
        controlsVisible = true
        exitArmed = false
        updateControls()
        scheduleControlsHide()
    }

    private fun hideControls() {
        hideBarJob?.cancel()
        listOf(binding.topBar, binding.infoBar).forEach {
            it.animate().cancel()
            it.visibility = View.GONE
        }
        controlsVisible = false
    }

    /**
     * 无操作 [BAR_HIDE_DELAY_MS] 后自动隐藏 —— **播放中和暂停中一样**。
     *
     * 暂停时不自动隐藏会让用户"每次都要按一次返回才能清屏"，那正是要避免的。
     */
    private fun scheduleControlsHide() {
        hideBarJob?.cancel()
        hideBarJob = lifecycleScope.launch {
            delay(BAR_HIDE_DELAY_MS)
            hideControls()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                if (controlsVisible) updateControls()
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun updateControls() {
        val exo = player
        // 顶部：文件名（常驻）+ 快进/快退的瞬时反馈（没有反馈时这块是空的）。
        binding.feedbackText.text = seekFeedback.orEmpty()

        val position = exo?.currentPosition ?: 0L
        val duration = exo?.duration ?: 0L
        binding.positionText.text = formatTime(position)
        binding.durationText.text =
            if (duration > 0L) formatTime(duration) else getString(R.string.player_time_unknown)
        binding.progressBar.progress = if (duration > 0L) {
            (position * PROGRESS_MAX / duration).toInt().coerceIn(0, PROGRESS_MAX)
        } else {
            0
        }
    }

    // --- 左侧选项面板（字幕 / 音轨） -------------------------------------------

    private fun toggleOptions() {
        if (optionsVisible) hideOptions() else showOptions()
    }

    private fun showOptions() {
        buildOptionsPanel()
        binding.optionsPanel.apply {
            animate().cancel()
            alpha = 0f
            translationX = -panelSlidePx()
            visibility = View.VISIBLE
            animate().alpha(1f).translationX(0f).setDuration(PANEL_ANIM_MS).start()
        }
        optionsVisible = true
        // 面板与信息条同时出现会叠两层 UI，收起信息条更清爽。
        hideControls()
        binding.optionsContent.post { focusPanelRow(optionRows.indexOfFirst { it.selected }) }
    }

    private fun hideOptions() {
        binding.optionsPanel.apply {
            animate().cancel()
            visibility = View.GONE
        }
        optionsVisible = false
    }

    /**
     * 用当前轨道表重建面板。
     *
     * 轨道数量少（一般个位数），所以每次直接重建，不做增量更新 —— 简单、无状态不同步的风险。
     */
    private fun buildOptionsPanel() {
        binding.optionsContent.removeAllViews()
        optionRows.clear()

        val exo = player ?: return
        val groups = exo.currentTracks.groups
        val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (textGroups.isEmpty() && audioGroups.isEmpty()) {
            binding.optionsContent.addView(hintView(getString(R.string.player_no_tracks)))
            return
        }

        if (textGroups.isNotEmpty()) {
            binding.optionsContent.addView(sectionView(getString(R.string.player_section_subtitle)))
            val textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val anySelected = textGroups.any { group -> (0 until group.length).any { group.isTrackSelected(it) } }
            addOptionRow(
                section = SECTION_SUBTITLE,
                label = getString(R.string.player_subtitle_off),
                selected = textDisabled || !anySelected,
            ) {
                val current = player ?: return@addOptionRow
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                subtitleAutoSelected = true
                rebuildPanelKeepingFocus()
            }
            addTrackRows(SECTION_SUBTITLE, textGroups)
        }

        if (audioGroups.isNotEmpty()) {
            binding.optionsContent.addView(sectionView(getString(R.string.player_section_audio)))
            addTrackRows(SECTION_AUDIO, audioGroups)
        }
    }

    private fun addTrackRows(section: Int, groups: List<Tracks.Group>) {
        groups.forEach { group ->
            for (index in 0 until group.length) {
                addOptionRow(
                    section = section,
                    label = trackLabel(group.getTrackFormat(index), index),
                    selected = group.isTrackSelected(index),
                ) {
                    selectTrack(group, index)
                }
            }
        }
    }

    private fun selectTrack(group: Tracks.Group, index: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        // 用户手动选过之后，onTracksChanged 的兜底不能再改他的选择。
        if (group.type == C.TRACK_TYPE_TEXT) subtitleAutoSelected = true
        rebuildPanelKeepingFocus()
    }

    private fun rebuildPanelKeepingFocus() {
        val focused = optionRows.indexOfFirst { it.view.isFocused }
        buildOptionsPanel()
        binding.optionsContent.post { focusPanelRow(focused) }
    }

    private fun focusPanelRow(preferred: Int) {
        val target = optionRows.getOrNull(preferred)
            ?: optionRows.firstOrNull { it.selected }
            ?: optionRows.firstOrNull()
        target?.view?.requestFocus()
    }

    /** 左右键在「字幕 / 音轨」两组之间跳转（组内上下键交给框架的焦点搜索）。 */
    private fun moveSection(direction: Int) {
        val currentIndex = optionRows.indexOfFirst { it.view.isFocused }
        if (currentIndex < 0) {
            focusPanelRow(0)
            return
        }
        val sections = optionRows.map { it.section }.distinct()
        val sectionIndex = sections.indexOf(optionRows[currentIndex].section)
        val target = sections.getOrNull(sectionIndex + direction) ?: return
        val candidates = optionRows.filter { it.section == target }
        (candidates.firstOrNull { it.selected } ?: candidates.first()).view.requestFocus()
    }

    private fun addOptionRow(section: Int, label: String, selected: Boolean, onSelect: () -> Unit) {
        val row = TextView(this).apply {
            text = getString(
                R.string.player_option_row,
                getString(if (selected) R.string.mark_selected else R.string.mark_unselected),
                label,
            )
            setTextColor(ContextCompat.getColor(this@PlayerActivity, if (selected) R.color.accent else R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, OPTION_TEXT_SP)
            setBackgroundResource(R.drawable.bg_entry)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(OPTION_ROW_MIN_HEIGHT_DP)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener { onSelect() }
            // 左右键在行上就拦掉：交给框架的焦点搜索的话，面板是单列布局，
            // 结果不可预期（可能什么都不发生）。行内拦截能保证「左右 = 换组」。
            setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.action == KeyEvent.ACTION_UP) moveSection(-1)
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_UP) moveSection(+1)
                        true
                    }

                    else -> false
                }
            }
        }
        binding.optionsContent.addView(row)
        optionRows += OptionRow(section, selected, row)
    }

    private fun sectionView(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun hintView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(8), 0, dp(8))
    }

    /**
     * 轨道显示名：优先播放器给的 `label`，其次把 ISO 语言码翻成中文，
     * 都没有才退化成「轨道 N」—— 电视上不该出现一串 `zh-Hans`。
     */
    private fun trackLabel(format: Format, index: Int): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        val language = format.language?.lowercase(Locale.US)
        language?.let { LANGUAGE_NAMES[it] }?.let { return it }
        language?.takeIf { it.isNotBlank() }?.let { return it }
        return getString(R.string.player_track_unnamed, index + 1)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun panelSlidePx(): Float = dp(PANEL_SLIDE_DP).toFloat()

    // --- 播放器 ---------------------------------------------------------------

    private fun initializePlayer() {
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val headers = headersFromIntent()
        binding.titleText.text = title

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Config.USER_AGENT)
            .setConnectTimeoutMs(Config.CONNECT_TIMEOUT_MS.toInt())
            .setReadTimeoutMs(Config.READ_TIMEOUT_MS.toInt())
            .setAllowCrossProtocolRedirects(true)
        // 数据源要求的额外头（夸克网页版的 CDN 直链必须带 Cookie/Referer/UA）。
        if (headers.isNotEmpty()) dataSourceFactory.setDefaultRequestProperties(headers)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                Config.MIN_BUFFER_MS,
                Config.MAX_BUFFER_MS,
                Config.BUFFER_FOR_PLAYBACK_MS,
                Config.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()

        // 新的播放器实例 = 新的轨道选择，兜底标记要跟着复位。
        subtitleAutoSelected = false

        // Prefer Chinese subtitles; `setSelectUndeterminedTextLanguage` also lets tracks
        // without a language tag score above zero.
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguages(*SUBTITLE_LANGUAGE_PREFERENCE)
            .setSelectUndeterminedTextLanguage(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.player_error_format, describe(error)),
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onTracksChanged(tracks: Tracks) {
                selectSubtitleFallback(exoPlayer, tracks)
                if (optionsVisible) rebuildPanelKeepingFocus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (controlsVisible) {
                    updateControls()
                    // 播放/暂停切换后重新计时：用户刚按过键，不该立刻被自动隐藏。
                    scheduleControlsHide()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (controlsVisible) updateControls()
                if (playbackState == Player.STATE_ENDED) {
                    // 播完自动回列表：列表页会把焦点放在刚播的这一条上，按一次「下」就是下一集。
                    finish()
                }
            }
        })

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

        binding.playerView.player = exoPlayer
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer
    }

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    /**
     * Mainstream-player subtitle look: white text with a black outline over a fully
     * transparent background, default (upright) typeface, size scaled to the video height.
     *
     * Two media3 defaults had to be overridden:
     *  - `CaptionStyleCompat.DEFAULT` means "follow the system caption style", and most TVs
     *    ship an opaque white-on-black style. That was the black box covering the picture.
     *  - Embedded subtitle styles were being applied as-is, so an ASS track that declares
     *    italics (or an odd font) rendered as odd italics.
     */
    private fun configureSubtitles() {
        binding.playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE,                            // text
                    Color.TRANSPARENT,                      // background — never cover the video
                    Color.TRANSPARENT,                      // window
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,   // black outline keeps it readable
                    Color.BLACK,
                    null,                                   // default typeface (not italic)
                ),
            )
            setFractionalTextSize(SUBTITLE_HEIGHT_FRACTION)
            setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
        }
    }

    /**
     * Last-resort subtitle selection.
     *
     * A track tagged with a real language (e.g. `en`) scores 0 against the default empty
     * language query, so ExoPlayer can end up with subtitles present but nothing selected.
     * Runs at most once per playback session, and never after a manual choice.
     */
    private fun selectSubtitleFallback(player: ExoPlayer, tracks: Tracks) {
        if (subtitleAutoSelected) return
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty()) return
        subtitleAutoSelected = true
        if (textGroups.any { group -> (0 until group.length).any { group.isTrackSelected(it) } }) return

        val target = textGroups.firstOrNull { group ->
            val language = group.getTrackFormat(0).language?.lowercase()
            language != null && CHINESE_LANGUAGE_PREFIXES.any { language.startsWith(it) }
        } ?: textGroups.first()

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
            .build()
    }

    /** Surfaces the real cause — `error_code_io_bad_http_status` alone is not actionable. */
    private fun describe(error: PlaybackException): String {
        val cause = error.cause
        return when {
            cause is HttpDataSource.InvalidResponseCodeException ->
                "HTTP ${cause.responseCode} ${cause.responseMessage.orEmpty()}".trim()
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ->
                getString(R.string.player_error_decoding)
            else -> error.errorCodeName
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun headersFromIntent(): Map<String, String> {
        val bundle = intent.getBundleExtra(EXTRA_HEADERS) ?: return emptyMap()
        return bundle.keySet().associateWith { bundle.getString(it).orEmpty() }
    }

    companion object {

        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_HEADERS = "extra_headers"

        /** Subtitle size as a fraction of video height (~48px at 1080p, ~97px at 4K). */
        private const val SUBTITLE_HEIGHT_FRACTION = 0.045f

        /** Distance of the subtitle block from the bottom, as a fraction of video height. */
        private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.08f

        /** 单次左右键的步长（也是长按连发时每一步的步长）。 */
        private const val SEEK_STEP_MS = 10_000L

        /** 按住多久算长按（之后进入连续 seek）。 */
        private const val LONG_PRESS_DELAY_MS = 400L

        /** 连续 seek 的间隔。 */
        private const val FAST_SEEK_INTERVAL_MS = 120L

        /** 播控 UI 无操作多久自动隐藏（播放中与暂停中相同）。 */
        private const val BAR_HIDE_DELAY_MS = 5_000L

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val SEEK_FEEDBACK_MS = 900L
        private const val EXIT_ARM_TIMEOUT_MS = 2_000L
        private const val PANEL_ANIM_MS = 160L
        private const val PANEL_SLIDE_DP = 24
        private const val OPTION_ROW_MIN_HEIGHT_DP = 56
        private const val OPTION_TEXT_SP = 20f

        private const val PROGRESS_MAX = 1000

        private const val SECTION_SUBTITLE = 0
        private const val SECTION_AUDIO = 1

        /** ISO 639 codes seen in the wild for Chinese subtitle tracks. */
        private val SUBTITLE_LANGUAGE_PREFERENCE =
            arrayOf("zh", "chi", "zho", "chs", "cht", "zh-Hans", "zh-Hant")

        private val CHINESE_LANGUAGE_PREFIXES = listOf("zh", "chi", "zho", "chs", "cht")

        /** 常见语言码 → 中文名。电视上不该出现 `zh-Hans` 这种串。 */
        private val LANGUAGE_NAMES = mapOf(
            "zh" to "中文", "chi" to "中文", "zho" to "中文", "chs" to "简体中文",
            "cht" to "繁体中文", "zh-hans" to "简体中文", "zh-hant" to "繁体中文",
            "en" to "英文", "eng" to "英文", "ja" to "日语", "jpn" to "日语",
            "ko" to "韩语", "kor" to "韩语", "fr" to "法语", "fra" to "法语",
            "de" to "德语", "deu" to "德语", "es" to "西班牙语", "spa" to "西班牙语",
            "ru" to "俄语", "rus" to "俄语", "th" to "泰语", "tha" to "泰语",
        )

        fun intent(
            context: Context,
            url: String,
            title: String,
            headers: Map<String, String> = emptyMap(),
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
            // 用 Bundle 而不是 HashMap：getSerializableExtra 在 API 33+ 已弃用，
            // 而 Bundle 一直是 Parcelable，不需要版本分支。
            .putExtra(EXTRA_HEADERS, Bundle().apply { headers.forEach { putString(it.key, it.value) } })
    }
}
