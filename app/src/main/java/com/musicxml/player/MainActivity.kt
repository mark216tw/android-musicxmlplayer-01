package com.musicxml.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

internal object NotificationPermissionPrompt {
    fun runAfterPlayback(
        sdk: Int,
        granted: Boolean,
        alreadyRequested: Boolean,
        playback: () -> Unit,
        requestPermission: () -> Unit
    ) {
        playback()
        if (sdk >= 33 && !granted && !alreadyRequested) requestPermission()
    }
}

private enum class TrackSort(val label: String) {
    RECENT("最近播放"), NAME("曲名"), COMPOSER("作曲者"), IMPORTED("加入日期"), DURATION("長度")
}

class MainActivity : Activity() {
    private lateinit var library: Library
    private lateinit var root: LinearLayout
    private lateinit var screen: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var stickyHost: LinearLayout
    private lateinit var ui: Context
    private var colors = UiColors(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val exportWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var playback: PlaybackService? = null
    private val service get() = checkNotNull(playback) { "播放器尚未連線" }
    private val score get() = playback?.score
    private val entry get() = playback?.entry
    private val volumes get() = service.volumes
    private val programs get() = service.programs
    private val speed get() = service.speed
    private val muted get() = service.muted
    private var solo: Int
        get() = service.solo
        set(value) { service.solo = value }
    private var metronome: Boolean
        get() = service.metronome
        set(value) { service.metronome = value }
    private var roll: PianoRoll? = null
    private var position: SeekBar? = null
    private var elapsedTime: TextView? = null
    private var scrubbing = false
    private var status: TextView? = null
    private var performanceText: TextView? = null
    private var activeInstrumentText: TextView? = null
    private var routeText: TextView? = null
    private var playButton: ImageButton? = null
    private var repeatButton: ImageButton? = null
    private var shuffleButton: ImageButton? = null
    private var allMuteButton: Button? = null
    private var miniTitle: TextView? = null
    private var miniButton: ImageButton? = null
    private var miniTrackId: String? = null
    private val lamps = mutableListOf<TextView>()
    private val volumeButtons = mutableListOf<Button>()
    private val muteButtons = mutableListOf<CheckBox>()
    private val soloButtons = mutableListOf<Button>()
    private val instrumentButtons = mutableListOf<Button>()
    private var query = ""
    private var trackSort = TrackSort.RECENT
    private var sortAscending = false
    private var page = "home"
    private var settingsReturnPage = "home"
    private var requestedPage = "home"
    private var playlistId: String? = null
    private var playerReturnPage = "home"
    private var notificationRequestPending = false
    private var bound = false
    private var busy = false
    private var durationBackfillRunning = false
    private var exportBusy = false
    private var exportGeneration = 0
    private var exportTask: Future<*>? = null
    private var resumed = false
    @Volatile private var alive = true
    internal var renderExport: (Context, WavExportSnapshot, File) -> Unit = { context, snapshot, output ->
        val exportScore = if (snapshot.includeMetronome) snapshot.score.withMetronome() else snapshot.score
        val plan = PerformanceCompiler.compile(exportScore, snapshot.style)
        SampleRenderer.wave(context, plan, snapshot.enabled, snapshot.volumes, snapshot.programs, output, snapshot.overrides)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playback = (binder as PlaybackService.LocalBinder).service
            playback?.trackChangedListener = {
                if (alive) runOnUiThread {
                    if ((requestedPage == "player" || page == "player") && score != null) showPlayer()
                    else if (page != "player") redraw()
                }
            }
            when {
                requestedPage == "player" && score != null -> showPlayer()
                requestedPage == "settings" -> showSettings()
                requestedPage == "tracks" -> showTracks()
                requestedPage == "playlists" -> showPlaylists()
                requestedPage == "playlist" -> showPlaylist(playlistId)
                else -> showHome()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playback?.trackChangedListener = null; playback = null; if (alive) showHome()
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            val p = playback
            routeText?.apply {
                val label = p?.audioRouteLabel ?: "音訊輸出：系統選擇"
                setTextIfChanged(label)
                contentDescription = "目前$label"
            }
            if (p != null && resumed && p.errorMessage != null) {
                val message = p.errorMessage!!; p.errorMessage = null; requestedPage = ""
                error(IllegalStateException(message))
            }
            val s = p?.score
            if (p != null && s != null) {
                val ms = p.currentPosition
                val tick = s.tickAt(ms)
                if (!scrubbing) {
                    position?.progress = tick
                    elapsedTime?.apply {
                        setTextIfChanged(time(ms))
                        contentDescription = "已播放 $text"
                    }
                }
                val bar = s.timeline.barAt(tick)
                val phaseText = when (p.transportPhase) {
                    TransportPhase.PREPARING -> "正在載入採樣音源…"
                    TransportPhase.STARTING -> "正在啟動播放…"
                    TransportPhase.RECONNECTING -> "正在重新連接音訊裝置…"
                    TransportPhase.ROUTE_UNAVAILABLE -> "找不到可用的音訊輸出，請連接裝置後按重試"
                    TransportPhase.COMPLETED -> "播放完畢"
                    TransportPhase.ERROR -> p.errorMessage ?: "播放錯誤"
                    TransportPhase.PLAYING, TransportPhase.PAUSED ->
                        "小節 ${bar?.number ?: "1"}"
                }
                status?.setTextIfChanged(phaseText)
                if (page == "player") {
                    val enabled = p.enabled()
                    val snapshot = PerformanceDisplay.snapshot(s, tick, enabled,
                        p.transportPhase == TransportPhase.PLAYING, p.programs, p.programOverrides)
                    if (!scrubbing) updateRollDisplay(snapshot, enabled)
                    refreshPartControls(enabled, snapshot.activeParts)
                }
                updateTransportButton(); updateMiniTransportButton()
                val miniState = when (p.transportPhase) {
                    TransportPhase.PREPARING -> "準備中"
                    TransportPhase.STARTING -> "正在啟動"
                    TransportPhase.PLAYING -> "正在播放"
                    TransportPhase.RECONNECTING -> "正在重新連接"
                    TransportPhase.ROUTE_UNAVAILABLE -> "音訊輸出不可用"
                    TransportPhase.PAUSED -> "已暫停"
                    TransportPhase.COMPLETED -> "播放完畢"
                    TransportPhase.ERROR -> "播放錯誤"
                }
                miniTitle?.setTextIfChanged("$miniState：${entry?.name}\n${time(ms)} / ${time(s.millisAt(s.endTick))}")
                if (requestedPage == "player" && page != "player") { showPlayer(); requestedPage = "player" }
                else if (page != "player" && miniTrackId != entry?.id) {
                    redraw(); handler.postDelayed(this, 50); return
                }
                if (resumed && page == "player" && p.transportPhase == TransportPhase.PLAYING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            handler.postDelayed(this, 50)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Appearance.dark(this)) R.style.AppTheme_Dark else R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        applyAppearance()
        library = Library(this)
        requestedPage = if (intent.action == PlaybackService.OPEN_PLAYER) "player" else savedInstanceState?.getString("page") ?: "home"
        settingsReturnPage = savedInstanceState?.getString("settingsReturn") ?: "home"
        playlistId = savedInstanceState?.getString("playlistId")
        showHome()
        bound = bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }
    private fun applyAppearance() {
        colors = UiColors(Appearance.dark(this), Appearance.hue(this))
        val theme = if (colors.dark) R.style.AppTheme_Dark else R.style.AppTheme_Light
        setTheme(theme); ui = ContextThemeWrapper(this, theme)
        window.decorView.setBackgroundColor(colors.background)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (colors.dark) 0 else flags, flags)
        } else {
            window.decorView.systemUiVisibility = if (colors.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    private fun redraw() { when (page) {
        "player" -> showPlayer()
        "settings" -> showSettings()
        "tracks" -> showTracks()
        "playlists" -> showPlaylists()
        "playlist" -> showPlaylist(playlistId)
        else -> showHome()
    } }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); applyAppearance(); redraw() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == PlaybackService.OPEN_PLAYER) { requestedPage = "player"; if (score != null) showPlayer() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("settingsReturn", settingsReturnPage)
        outState.putString("playlistId", playlistId)
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 16f) = TextView(ui).apply {
        text = value; textSize = size; setTextColor(colors.ink); setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private fun button(value: String, action: () -> Unit) = Button(ui).apply { text = value; isAllCaps = false; setOnClickListener { action() } }
    private fun iconButton(icon: Int, description: String, action: () -> Unit) = ImageButton(ui).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(colors.ink)
        background = getDrawable(android.R.drawable.list_selector_background)
        contentDescription = description; tooltipText = description
        setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun circle(color: Int, strokeColor: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        strokeColor?.let { setStroke(dp(strokeWidth), it) }
    }
    private fun card(padding: Int = 16) = column().apply {
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = rounded(colors.surface, 18, colors.line)
    }
    private fun primaryButton(value: String, action: () -> Unit) = button(value, action).apply {
        minHeight = dp(52); backgroundTintList = ColorStateList.valueOf(colors.accent)
        setTextColor(colors.onAccent); typeface = Typeface.DEFAULT_BOLD
    }
    private fun accentStates() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(colors.accent, colors.accent, colors.muted)
    )
    private fun tint(button: CompoundButton) { button.buttonTintList = accentStates() }
    private fun tint(bar: SeekBar) {
        bar.thumbTintList = ColorStateList.valueOf(colors.accent)
        if (bar !is HueSeekBar) bar.progressTintList = ColorStateList.valueOf(colors.accent)
    }
    private fun base(title: String, gear: Boolean = false, back: (() -> Unit)? = null, more: ((View) -> Unit)? = null) {
        roll = null; position = null; elapsedTime = null
        status = null; performanceText = null; activeInstrumentText = null; routeText = null
        playButton = null; repeatButton = null; shuffleButton = null; allMuteButton = null; miniTitle = null; miniButton = null; miniTrackId = null
        lamps.clear(); volumeButtons.clear(); muteButtons.clear(); soloButtons.clear(); instrumentButtons.clear(); scrubbing = false
        root = column().apply { setPadding(dp(16), dp(12), dp(16), dp(20)); setBackgroundColor(colors.background) }
        contentScroll = ScrollView(ui).apply { tag = "contentScroll"; isFillViewport = true; setBackgroundColor(colors.background); addView(root) }
        stickyHost = column().apply { tag = "stickyHost"; visibility = View.GONE; setBackgroundColor(colors.surface) }
        screen = column().apply {
            tag = "screenRoot"; setBackgroundColor(colors.background)
            addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f)); addView(stickyHost, LinearLayout.LayoutParams(-1, -2))
        }
        if (Build.VERSION.SDK_INT >= 30) screen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            contentScroll.setPadding(0, 0, 0, if (stickyHost.visibility == View.VISIBLE) 0 else bars.bottom)
            stickyHost.setPadding(dp(12), dp(4), dp(12), bars.bottom + dp(8)); insets
        }
        setContentView(screen); screen.requestApplyInsets()
        root.addView(row().apply {
            if (back != null) addView(ImageButton(ui).apply {
                setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(colors.ink)
                background = getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "返回"; tooltipText = "返回"; setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(title, 24f), LinearLayout.LayoutParams(0, -2, 1f))
            if (gear) addView(ImageButton(ui).apply {
                setImageResource(R.drawable.ic_settings); imageTintList = ColorStateList.valueOf(colors.ink)
                background = getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "設定"; tooltipText = "設定"; setPadding(dp(12), dp(12), dp(12), dp(12))
                 setOnClickListener { settingsReturnPage = page; showSettings() }
             }, LinearLayout.LayoutParams(dp(48), dp(48)))
            if (more != null) addView(ImageButton(ui).apply {
                setImageResource(R.drawable.ic_more_vert); imageTintList = ColorStateList.valueOf(colors.ink)
                background = getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "更多"; tooltipText = contentDescription
                setPadding(dp(12), dp(12), dp(12), dp(12)); tag = "moreButton"
                setOnClickListener { more(this) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
    }
    private fun installMiniPlayer() {
        val current = entry ?: run { stickyHost.removeAllViews(); stickyHost.visibility = View.GONE; return }
        stickyHost.removeAllViews()
        miniTrackId = current.id
        val panel = row().apply {
            tag = "miniPlayer"
            background = rounded(colors.surface, 18, colors.line)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { playerReturnPage = page; showPlayer() }
            val initial = current.name.trim().firstOrNull()?.uppercase() ?: "M"
            addView(text(initial, 22f).apply {
                gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                background = rounded(PartColors.color(kotlin.math.abs(current.id.hashCode()), colors.dark), 14)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
            miniTitle = text(current.name, 14f).apply {
                maxLines = 2; setPadding(0, 0, dp(8), 0)
            }.also { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            miniButton = iconButton(R.drawable.ic_play, transportLabel()) { userTogglePlayback() }.apply {
                tag = "miniPlayPauseButton"
                background = rounded(colors.accent, 24)
                imageTintList = ColorStateList.valueOf(colors.onAccent)
            }.also { addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
            addView(iconButton(android.R.drawable.ic_media_next, "下一首") { service.skipNext() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        stickyHost.addView(panel)
        stickyHost.visibility = View.VISIBLE
        updateMiniTransportButton()
        screen.requestApplyInsets()
    }

    private fun showHome() {
        page = "home"; library = Library(this)
        base(getString(R.string.app_name), gear = true)
        root.addView(text("你的樂譜，現在開演", 30f).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(text("整理 MusicXML 曲目、建立播放清單，或從上次的位置繼續。", 15f).apply { setTextColor(colors.muted) })
        root.addView(card().apply {
            tag = "playlistHomeCard"; isClickable = true; isFocusable = true
            contentDescription = "播放清單，建立自己的演奏順序"
            setOnClickListener { showPlaylists() }
            addView(text("播放清單", 23f).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(text("建立自己的演奏順序", 14f).apply { setTextColor(colors.muted) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(12) })
        root.addView(card().apply {
            tag = "tracksHomeCard"; isClickable = true; isFocusable = true
            contentDescription = "所有曲目，搜尋與排序"
            setOnClickListener { showTracks() }
            addView(text("所有曲目", 23f).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(text("${library.entries.size} 首樂曲 · 搜尋與排序", 14f).apply { setTextColor(colors.muted) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        val recent = library.entries.sortedByDescending { maxOf(it.lastPlayed, it.imported) }.take(3)
        if (recent.isNotEmpty()) {
            root.addView(text("最近播放", 21f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            recent.forEach { e -> root.addView(trackRow(e) { playEntries(recent.map { it.id }, e.id) }) }
        }
        installMiniPlayer(); backfillDurations()
    }
    private fun showSettings() {
        page = "settings"; base("設定", back = { showHome() })
        root.addView(text("顯示模式", 22f))
        root.addView(text("立即套用並保存；切換模式不會中斷音樂。", 14f))
        val group = RadioGroup(ui).apply { orientation = RadioGroup.VERTICAL }
        val selected = Appearance.mode(this)
        listOf(DisplayMode.SYSTEM to "系統（跟隨手機設定）", DisplayMode.LIGHT to "淺色", DisplayMode.DARK to "深色").forEach { (mode, label) ->
            group.addView(RadioButton(ui).apply {
                id = View.generateViewId(); tag = mode; text = label; setTextColor(colors.ink)
                minHeight = dp(56); isChecked = selected == mode; tint(this)
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            val mode = group.findViewById<RadioButton>(id)?.tag as? DisplayMode ?: return@setOnCheckedChangeListener
            Appearance.setMode(this, mode); applyAppearance(); showSettings()
        }
        root.addView(group)
        root.addView(text("主題色彩", 22f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(text("選擇預設色彩，或拖曳 Hue 滑桿自訂。", 14f).apply { setTextColor(colors.muted) })
        val presetButtons = mutableListOf<Pair<ThemeColorPreset, TextView>>()
        val presets = column().apply { tag = "themePresetGrid" }
        Appearance.themePresets.chunked(3).forEach { presetRow ->
            presets.addView(row().apply {
                presetRow.forEach { preset ->
                    addView(column().apply {
                        gravity = Gravity.CENTER
                        val selectedHue = Appearance.hue(this@MainActivity) == preset.hue
                        val swatch = text(if (selectedHue) "✓" else "", 20f).apply {
                            tag = "themePreset:${preset.hue}"; gravity = Gravity.CENTER
                            typeface = Typeface.DEFAULT_BOLD; setTextColor(if (colors.dark) Color.BLACK else Color.WHITE)
                            background = circle(Appearance.accent(preset.hue, colors.dark), if (selectedHue) colors.ink else colors.line, if (selectedHue) 3 else 1)
                            contentDescription = "${preset.name}${if (selectedHue) "，已選取" else ""}"
                            isClickable = true; isFocusable = true
                            setOnClickListener {
                                Appearance.setHue(this@MainActivity, preset.hue)
                                applyAppearance(); showSettings()
                            }
                        }
                        presetButtons += preset to swatch
                        addView(swatch, LinearLayout.LayoutParams(dp(54), dp(54)))
                        addView(text(preset.name, 13f).apply { gravity = Gravity.CENTER })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        root.addView(presets)
        val customPreview = View(ui).apply {
            tag = "customHuePreview"; contentDescription = "目前自訂主題色"
            background = circle(colors.accent, colors.line, 1)
        }
        val slider = HueSeekBar(ui).apply {
            tag = "accentHueSlider"; progress = Appearance.hue(this@MainActivity); tint(this)
            contentDescription = "自訂主題色 Hue，現在是 $progress 度"
        }
        fun previewHue(hue: Int) {
            Appearance.setHue(this, hue)
            colors = UiColors(Appearance.dark(this), hue)
            customPreview.background = circle(colors.accent, colors.line, 1)
            slider.thumbTintList = ColorStateList.valueOf(colors.accent)
            slider.contentDescription = "自訂主題色 Hue，現在是 $hue 度"
            presetButtons.forEach { (preset, view) ->
                val isSelected = preset.hue == hue
                view.text = if (isSelected) "✓" else ""
                view.background = circle(Appearance.accent(preset.hue, colors.dark), if (isSelected) colors.ink else colors.line, if (isSelected) 3 else 1)
                view.contentDescription = "${preset.name}${if (isSelected) "，已選取" else ""}"
            }
            (0 until group.childCount).mapNotNull { group.getChildAt(it) as? RadioButton }.forEach(::tint)
            updateMiniTransportButton()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                if (user || value != Appearance.hue(this@MainActivity)) previewHue(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) { applyAppearance(); showSettings() }
        })
        root.addView(row().apply {
            addView(customPreview, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            addView(slider, LinearLayout.LayoutParams(0, dp(44), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(12) })
        root.addView(appInfoFooter())
        installMiniPlayer()
    }
    private fun error(t: Throwable) { AlertDialog.Builder(ui).setTitle("無法完成").setMessage(t.message ?: "請重新嘗試").setPositiveButton("知道了", null).show() }
    private fun async(action: () -> Unit) {
        if (busy) return
        busy = true
        worker.execute {
            try { action() } catch (t: Exception) { runOnUiThread { if (alive) error(t) } }
            finally { runOnUiThread { busy = false } }
        }
    }
    private fun exportAsync(action: () -> Unit) {
        if (exportBusy) { Toast.makeText(this, "正在匯出，請等待完成", Toast.LENGTH_SHORT).show(); return }
        exportBusy = true
        val generation = ++exportGeneration
        try {
            exportTask = exportWorker.submit {
                try { action() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                catch (t: Exception) { runOnUiThread { if (alive && generation == exportGeneration) error(t) } }
                finally { runOnUiThread { if (alive && generation == exportGeneration) exportBusy = false } }
            }
        } catch (t: RejectedExecutionException) {
            exportBusy = false
            if (alive) error(t)
        }
    }
    private fun imported(bytes: ByteArray, parsed: Score) {
        library = Library(this); val added = library.add(bytes, parsed); query = ""
        showTracks(); Toast.makeText(this, "已匯入：${added.name}", Toast.LENGTH_SHORT).show()
    }
    private fun backfillDurations() {
        if (durationBackfillRunning) return
        val missing = library.entries.filter { it.durationMillis == null }.map { it.id to library.file(it) }
        if (missing.isEmpty()) return
        durationBackfillRunning = true
        worker.execute {
            val durations = missing.mapNotNull { (id, file) ->
                runCatching { id to MusicXml.parse(file.readBytes()).let { it.millisAt(it.endTick) } }.getOrNull()
            }
            runOnUiThread {
                val changed = library.mergeDurations(durations.toMap())
                durationBackfillRunning = false
                if (changed && alive && page == "tracks") showTracks()
            }
        }
    }
    private fun openDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 10)
    }

    private fun importDemo() { async {
        val bytes = assets.open("demo.musicxml").use { it.readBytes() }
        val parsed = MusicXml.parse(bytes)
        runOnUiThread { if (alive) imported(bytes, parsed) }
    } }

    private fun sortedTracks(source: List<LibraryEntry>): List<LibraryEntry> {
        val comparator = when (trackSort) {
            TrackSort.RECENT -> compareBy<LibraryEntry> { maxOf(it.lastPlayed, it.imported) }
            TrackSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            TrackSort.COMPOSER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.composer }
            TrackSort.IMPORTED -> compareBy { it.imported }
            TrackSort.DURATION -> compareBy { it.durationMillis ?: -1 }
        }
        return source.sortedWith(if (sortAscending) comparator else comparator.reversed())
    }

    private fun filteredTracks() = sortedTracks(library.entries.filter {
        "${it.name} ${it.composer} ${it.instruments}".contains(query.trim(), true)
    })

    private fun trackRow(e: LibraryEntry, action: () -> Unit) = card(12).apply {
        tag = "track:${e.id}"; isClickable = true; isFocusable = true; setOnClickListener { action() }
        addView(text(e.name, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(text(e.composer.ifBlank { "未標示作曲者" }, 13f).apply { setTextColor(colors.muted) })
        addView(row().apply {
            addView(text(e.instruments.ifBlank { "未標示樂器" }, 12f).apply { setTextColor(colors.muted) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(e.durationMillis?.let(::time) ?: "--:--", 12f).apply { setTextColor(colors.muted) })
        })
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }

    private fun playEntries(ids: List<String>, startId: String, shuffle: Boolean = false) {
        if (playback == null) { Toast.makeText(this, "播放器正在連線…", Toast.LENGTH_SHORT).show(); return }
        playerReturnPage = page
        requestedPage = "player"
        explicitPlayback { service.playQueue(ids, startId, shuffle) }
    }

    private fun showTracks() {
        page = "tracks"; library = Library(this)
        base("所有曲目", gear = true, back = { showHome() })
        root.addView(row().apply {
            addView(primaryButton("＋ 匯入") { openDocument() }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(button("示範曲") { importDemo() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        })
        val search = EditText(ui).apply {
            hint = "搜尋曲名、作曲者或樂器"; setSingleLine(); setText(query)
            setTextColor(colors.ink); setHintTextColor(colors.muted); tag = "trackSearch"
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        val controls = row()
        val sortButton = button("排序：${trackSort.label}") {
            AlertDialog.Builder(ui).setTitle("曲目排序")
                .setSingleChoiceItems(TrackSort.entries.map { it.label }.toTypedArray(), trackSort.ordinal) { dialog, which ->
                    trackSort = TrackSort.entries[which]
                    sortAscending = trackSort !in setOf(TrackSort.RECENT, TrackSort.IMPORTED)
                    dialog.dismiss(); showTracks()
                }.setNegativeButton("取消", null).show()
        }
        controls.addView(sortButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(button(if (sortAscending) "升冪 ↑" else "降冪 ↓") { sortAscending = !sortAscending; showTracks() }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(controls)
        val list = column().apply { tag = "libraryList" }
        fun refresh() {
            list.removeAllViews()
            val results = filteredTracks()
            if (results.isEmpty()) list.addView(text(if (library.entries.isEmpty()) "還沒有曲目。請匯入 MusicXML，或先加入示範曲。" else "找不到符合的曲目。"))
            results.forEach { e -> list.addView(column().apply {
                addView(trackRow(e) { playEntries(results.map { it.id }, e.id) })
                addView(row().apply {
                    addView(button("播放") { playEntries(results.map { it.id }, e.id) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(button("加入清單") { addTrackToPlaylist(e) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(button("更多") { showTrackActions(e) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        root.addView(list)
        refresh(); backfillDurations(); installMiniPlayer()
    }

    private fun showPlaylists() {
        page = "playlists"; library = Library(this)
        base("播放清單", gear = true, back = { showHome() })
        root.addView(primaryButton("＋ 新增播放清單") { playlistNameDialog("新增播放清單") { library.createPlaylist(it); showPlaylists() } })
        if (library.playlists.isEmpty()) root.addView(text("還沒有播放清單。建立清單後即可安排自己的演奏順序。"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        library.playlists.sortedByDescending { it.updated }.forEach { playlist ->
            val duration = playlist.trackIds.mapNotNull { id -> library.entries.firstOrNull { it.id == id }?.durationMillis }.sum()
            root.addView(card().apply {
                isClickable = true; setOnClickListener { showPlaylist(playlist.id) }
                addView(text(playlist.name, 20f).apply { typeface = Typeface.DEFAULT_BOLD })
                addView(text("${playlist.trackIds.size} 首 · ${time(duration)}", 13f).apply { setTextColor(colors.muted) })
                addView(row().apply {
                    addView(button("開啟") { showPlaylist(playlist.id) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(button("改名") { playlistNameDialog("重新命名", playlist.name) { library.renamePlaylist(playlist, it); showPlaylists() } }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(button("刪除") { confirmDeletePlaylist(playlist) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        installMiniPlayer()
    }

    private fun showPlaylist(id: String?) {
        library = Library(this)
        val playlist = library.playlists.firstOrNull { it.id == id } ?: run { showPlaylists(); return }
        page = "playlist"; playlistId = playlist.id
        base(playlist.name, gear = true, back = { showPlaylists() })
        root.addView(row().apply {
            addView(primaryButton("播放全部") {
                playlist.trackIds.firstOrNull()?.let { playEntries(playlist.trackIds, it) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(button("隨機播放") {
                playlist.trackIds.randomOrNull()?.let { playEntries(playlist.trackIds, it, shuffle = true) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        })
        root.addView(button("＋ 加入曲目") { addTracksToPlaylist(playlist) })
        root.addView(text("長按曲目並拖曳可調整順序", 13f).apply { setTextColor(colors.muted) })
        if (playlist.trackIds.isEmpty()) root.addView(text("這個播放清單還沒有曲目。"))
        var dragSource: View? = null
        var dragTarget: View? = null
        fun clearDragFeedback() {
            dragSource?.alpha = 1f
            dragTarget?.apply {
                alpha = 1f
                background = rounded(colors.surface, 16, colors.line)
            }
            dragSource = null
            dragTarget = null
        }
        fun highlightDragTarget(target: View) {
            if (dragTarget !== target) {
                dragTarget?.apply {
                    alpha = 1f
                    background = rounded(colors.surface, 16, colors.line)
                }
                dragTarget = target
            }
            target.alpha = 0.92f
            target.background = rounded(colors.surface, 16, colors.accent)
        }
        playlist.trackIds.mapNotNull { trackId -> library.entries.firstOrNull { it.id == trackId } }.forEachIndexed { index, e ->
            val item = row().apply {
                tag = "playlistTrack:${e.id}"; background = rounded(colors.surface, 16, colors.line)
                setPadding(dp(8), dp(6), dp(4), dp(6)); isLongClickable = true
                addView(text("${index + 1}", 14f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), dp(52)))
                addView(column().apply {
                    addView(text(e.name, 16f).apply { typeface = Typeface.DEFAULT_BOLD })
                    addView(text(e.composer.ifBlank { "未標示作曲者" }, 12f).apply { setTextColor(colors.muted) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("移除") { library.removeFromPlaylist(playlist, e.id); showPlaylist(playlist.id) }, LinearLayout.LayoutParams(-2, dp(44)))
                setOnClickListener { playEntries(playlist.trackIds, e.id) }
                setOnLongClickListener { view ->
                    clearDragFeedback()
                    dragSource = view
                    view.alpha = 0.55f
                    if (!view.startDragAndDrop(ClipData.newPlainText("track", e.id), View.DragShadowBuilder(view), index, 0)) {
                        clearDragFeedback()
                    }
                    true
                }
                setOnDragListener { view, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> event.localState is Int
                        DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                            highlightDragTarget(view); true
                        }
                        DragEvent.ACTION_DRAG_EXITED -> {
                            if (dragTarget === view) {
                                view.alpha = if (dragSource === view) 0.55f else 1f
                                view.background = rounded(colors.surface, 16, colors.line)
                                dragTarget = null
                            }
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            val sourceIndex = event.localState as? Int
                            clearDragFeedback()
                            if (sourceIndex != null) library.moveInPlaylist(playlist, sourceIndex, index)
                            showPlaylist(playlist.id); true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> { clearDragFeedback(); true }
                        else -> true
                    }
                }
            }
            root.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        installMiniPlayer()
    }

    private fun playlistNameDialog(title: String, value: String = "", action: (String) -> Unit) {
        val input = EditText(ui).apply { setText(value); setSingleLine(); hint = "播放清單名稱" }
        AlertDialog.Builder(ui).setTitle(title).setView(input).setPositiveButton("儲存") { _, _ ->
            runCatching { action(input.text.toString()) }.onFailure(::error)
        }.setNegativeButton("取消", null).show()
    }

    private fun confirmDeletePlaylist(playlist: Playlist) {
        AlertDialog.Builder(ui).setTitle("刪除「${playlist.name}」？")
            .setMessage("只會刪除播放清單，不會刪除曲目。")
            .setPositiveButton("刪除") { _, _ -> library.removePlaylist(playlist); showPlaylists() }
            .setNegativeButton("取消", null).show()
    }

    private fun addTrackToPlaylist(track: LibraryEntry) {
        library = Library(this)
        if (library.playlists.isEmpty()) {
            playlistNameDialog("建立播放清單") { name ->
                val playlist = library.createPlaylist(name); library.addToPlaylist(playlist, listOf(track.id)); showTracks()
            }
            return
        }
        AlertDialog.Builder(ui).setTitle("將「${track.name}」加入")
            .setItems(library.playlists.map { it.name }.toTypedArray()) { _, which ->
                library.addToPlaylist(library.playlists[which], listOf(track.id))
                Toast.makeText(this, "已加入 ${library.playlists[which].name}", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun addTracksToPlaylist(playlist: Playlist) {
        val candidates = library.entries.filter { it.id !in playlist.trackIds }
        if (candidates.isEmpty()) { Toast.makeText(this, "沒有其他可加入的曲目", Toast.LENGTH_SHORT).show(); return }
        val selected = BooleanArray(candidates.size)
        AlertDialog.Builder(ui).setTitle("加入曲目")
            .setMultiChoiceItems(candidates.map { it.name }.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("加入") { _, _ ->
                library.addToPlaylist(playlist, candidates.indices.filter { selected[it] }.map { candidates[it].id })
                showPlaylist(playlist.id)
            }.setNegativeButton("取消", null).show()
    }

    private fun showTrackActions(track: LibraryEntry) {
        AlertDialog.Builder(ui).setTitle(track.name).setItems(arrayOf("重新命名", "刪除曲目")) { _, which ->
            if (which == 0) {
                val input = EditText(ui).apply { setText(track.name); setSingleLine() }
                AlertDialog.Builder(ui).setTitle("重新命名").setView(input).setPositiveButton("儲存") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        track.name = name; library.save()
                        if (entry?.id == track.id) { entry?.name = name; service.saveSettings() }
                        showTracks()
                    }
                }.setNegativeButton("取消", null).show()
            } else AlertDialog.Builder(ui).setTitle("刪除「${track.name}」？")
                .setMessage("將移除 App 中保存的樂譜與所有播放清單中的項目。")
                .setPositiveButton("刪除") { _, _ -> runCatching {
                    playback?.removeTrack(track.id)
                    library.remove(track); showTracks()
                }.onFailure(::error) }.setNegativeButton("取消", null).show()
        }.show()
    }

    private fun appInfoFooter() = column().apply {
        tag = "appInfoFooter"; setPadding(0, dp(20), 0, 0)
        val buildNumber = getString(R.string.build_number)
        routeText = text(playback?.audioRouteLabel ?: "音訊輸出：系統選擇", 13f).apply {
            tag = "audioRoute"; setTextColor(colors.muted); contentDescription = "目前$text"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { addView(it) }
        text("版本 ${BuildConfig.APP_VERSION_NAME}", 12f).apply { tag = "appVersion"; setTextColor(colors.muted) }.also { addView(it) }
        text("Build $buildNumber", 12f).apply { tag = "appBuild"; setTextColor(colors.muted) }.also { addView(it) }
    }
    @Deprecated("Android activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 11 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return; if (score == null) return
            val snapshot = service.exportSnapshot()
            Toast.makeText(this, "正在匯出採樣音訊…", Toast.LENGTH_SHORT).show()
            exportAsync {
                val wave = File(cacheDir, "export-${java.util.UUID.randomUUID()}.wav")
                try {
                    renderExport(applicationContext, snapshot, wave)
                    contentResolver.openOutputStream(uri)?.use { output ->
                        wave.inputStream().use { it.copyTo(output) }
                    } ?: error("無法儲存匯出檔案")
                } finally { wave.delete() }
                runOnUiThread { if (alive) Toast.makeText(this, "已匯出", Toast.LENGTH_SHORT).show() }
            }
            return
        }
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "正在匯入樂譜…", Toast.LENGTH_SHORT).show()
        async {
            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = stream.read(buffer)
                while (count != -1) {
                    require(output.size() + count <= 20 * 1024 * 1024) { "檔案超過 20 MB" }
                    output.write(buffer, 0, count); count = stream.read(buffer)
                }; output.toByteArray()
            } ?: error("無法開啟檔案")
            val parsed = MusicXml.parse(bytes)
            runOnUiThread { if (alive) try { imported(bytes, parsed) } catch (t: Exception) { error(t) } }
        }
    }
    private fun open(e: LibraryEntry, parsed: Score) {
        service.open(e, parsed); showPlayer()
        if (parsed.warnings.isNotEmpty()) AlertDialog.Builder(ui).setTitle("樂譜播放提示")
            .setMessage(parsed.warnings.joinToString("\n")).setPositiveButton("知道了", null).show()
    }
    private fun enabled() = service.enabled()
    private fun updateRollDisplay(tick: Int, enabled: List<Boolean>, playing: Boolean, programs: List<Int>, overrides: List<Boolean>) {
        val s = score ?: return
        updateRollDisplay(PerformanceDisplay.snapshot(s, tick, enabled, playing, programs, overrides), enabled)
    }
    private fun updateRollDisplay(snapshot: PerformanceSnapshot, enabled: List<Boolean>) {
        val pianoRoll = roll ?: return
        pianoRoll.updatePlayback(snapshot, enabled)
        performanceText?.apply {
            setTextIfChanged(pianoRoll.performanceContext)
            contentDescription = if (pianoRoll.performanceContext.isBlank()) "目前沒有演奏說明"
                else "目前演奏說明：${pianoRoll.performanceContext}"
        }
        val labels = SpannableStringBuilder()
        pianoRoll.activeParts.sorted().forEach { part ->
            val label = pianoRoll.instrumentLabels.getOrNull(part) ?: return@forEach
            if (labels.isNotEmpty()) labels.append("　")
            val start = labels.length
            labels.append(label)
            labels.setSpan(ForegroundColorSpan(PartColors.color(part, colors.dark)), start, labels.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            labels.setSpan(StyleSpan(Typeface.BOLD), start, labels.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        activeInstrumentText?.apply {
            if (text.toString() != labels.toString()) text = labels
            contentDescription = if (labels.isEmpty()) "目前沒有發聲樂器" else "目前發聲樂器：$labels"
        }
    }
    private fun refreshPartControls(enabled: List<Boolean> = enabled(), active: Set<Int> = emptySet()) {
        val s = score ?: return
        s.parts.indices.forEach { i ->
            val isEnabled = enabled.getOrElse(i) { true }
            val isActive = i in active
            val color = if (isEnabled) PartColors.color(i, colors.dark) else colors.muted
            val alpha = if (isActive || !isEnabled) 1f else 0.65f
            lamps.getOrNull(i)?.apply { setTextIfChanged((if (isActive) "● " else "○ ") + s.parts[i].name); setTextColor(color); this.alpha = alpha }
            volumeButtons.getOrNull(i)?.apply {
                setTextIfChanged("音量：${volumes[i]}")
                setTextColor(color); this.alpha = alpha
                contentDescription = "${s.parts[i].name}：音量 ${volumes[i]}，開啟音量選單"
            }
            muteButtons.getOrNull(i)?.apply {
                isChecked = muted[i]
                contentDescription = "${s.parts[i].name}：${if (muted[i]) "取消靜音" else "靜音"}"
            }
            soloButtons.getOrNull(i)?.apply {
                setTextIfChanged(if (solo == i) "取消獨奏" else "獨奏")
                contentDescription = "${s.parts[i].name}：${if (solo == i) "取消獨奏" else "獨奏"}"
            }
            instrumentButtons.getOrNull(i)?.apply {
                setTextIfChanged(InstrumentNames.name(programs[i], s.parts[i].percussion))
                contentDescription = "${s.parts[i].name}：選擇樂器，目前為 ${text}"
            }
        }
        allMuteButton?.apply {
            setTextIfChanged(if (muted.all { it }) "取消全部靜音" else "全部靜音")
            contentDescription = text
        }
    }
    private fun refreshPlayerState() {
        val s = score ?: return
        val enabled = enabled()
        val snapshot = PerformanceDisplay.snapshot(s, s.tickAt(service.currentPosition), enabled,
            service.isPlaying, programs, service.programOverrides)
        updateRollDisplay(snapshot, enabled)
        refreshPartControls(enabled, snapshot.activeParts)
    }
    private fun showPlayer() {
        val s = score ?: return
        requestedPage = ""
        page = "player"; base("現正播放", back = { returnFromPlayer() }, more = { showPlayerMenu(it) })
        root.addView(text(entry?.name.orEmpty(), 28f).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(text("${s.composer.ifBlank { "未標示作曲者" }} · ${s.parts.size} 個聲部", 13f))
        root.addView(row().apply {
            tag = "measureEffectsRow"
            status = text(if (service.transportPhase == TransportPhase.PREPARING) "正在載入採樣音源…" else "即時採樣播放").apply {
                tag = "measureStatus"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { addView(it, LinearLayout.LayoutParams(-2, -2)) }
            performanceText = text("", 13f).apply {
                tag = "performanceContext"; minHeight = dp(32); gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }.also { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }, LinearLayout.LayoutParams(-1, -2))
        activeInstrumentText = text("", 13f).apply {
            tag = "activeInstruments"; minHeight = dp(32); gravity = Gravity.CENTER_VERTICAL
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        root.addView(row().apply {
            addView(text("音符卷軸", 16f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_measure, "跳至小節") {
                showMeasureDialog(s)
            }.apply { tag = "measureButton" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        roll = PianoRoll(ui, s, colors.dark) { service.seek(it) }.also {
            root.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        updateRollDisplay(s.tickAt(service.currentPosition), enabled(), service.isPlaying, programs, service.programOverrides)
        root.addView(text("橫向為時間，縱向為音高；點選可跳轉。", 12f))
        stickyHost.addView(row().apply {
            tag = "positionRow"
            elapsedTime = text(time(service.currentPosition), 12f).apply {
                tag = "elapsedTime"; contentDescription = "已播放 $text"
                includeFontPadding = false; gravity = Gravity.CENTER
            }.also { addView(it, LinearLayout.LayoutParams(-2, dp(48))) }
            position = SeekBar(ui).apply {
                tag = "positionSeekBar"
                max = s.endTick; progress = s.tickAt(service.currentPosition)
                tint(this)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) {
                        elapsedTime?.apply {
                            setTextIfChanged(time(s.millisAt(value)))
                            contentDescription = "已播放 $text"
                        }
                        updateRollDisplay(value, enabled(), service.isPlaying, programs, service.programOverrides)
                    } }
                    override fun onStartTrackingTouch(bar: SeekBar?) { scrubbing = true }
                    override fun onStopTrackingTouch(bar: SeekBar?) { service.seek(bar?.progress ?: 0); scrubbing = false }
                })
            }.also { addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
            text(time(s.millisAt(s.endTick)), 12f).apply {
                tag = "totalTime"; contentDescription = "總時長 $text"
                includeFontPadding = false; gravity = Gravity.CENTER
            }.also { addView(it, LinearLayout.LayoutParams(-2, dp(48))) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        stickyHost.addView(row().apply {
            tag = "transportRow"
            shuffleButton = iconButton(R.drawable.ic_shuffle, "開啟隨機播放") { service.setShuffle(!service.shuffleEnabled); updateShuffleButton() }
                .apply { tag = "shuffleButton" }.also { addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
            addView(iconButton(R.drawable.ic_skip_previous, "上一首") { service.skipPrevious() }.apply { tag = "previousButton" }, LinearLayout.LayoutParams(0, dp(52), 1f))
            playButton = iconButton(R.drawable.ic_play, transportLabel()) { userTogglePlayback() }.apply {
                tag = "playPauseButton"
                background = circle(colors.accent)
                imageTintList = ColorStateList.valueOf(colors.onAccent)
            }
                .also { addView(it, LinearLayout.LayoutParams(dp(52), dp(52))) }
            addView(iconButton(R.drawable.ic_skip_next, "下一首") { service.skipNext() }.apply { tag = "nextButton" }, LinearLayout.LayoutParams(0, dp(52), 1f))
            repeatButton = iconButton(R.drawable.ic_repeat, "開啟清單循環") { service.cycleRepeatMode(); updateRepeatButton() }
                .apply { tag = "repeatButton" }.also { addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        })
        updateTransportButton(); updateRepeatButton(); updateShuffleButton()
        stickyHost.visibility = View.VISIBLE
        screen.requestApplyInsets()
        root.addView(row().apply {
            addView(button("播放速度：${SpeedOptions.labels[SpeedOptions.indexOf(speed)]}") {
            val dialog = AlertDialog.Builder(ui)
                .setTitle("播放速度")
                .setSingleChoiceItems(SpeedOptions.labels.toTypedArray(), SpeedOptions.indexOf(speed)) { dialog, which ->
                    try {
                        service.setSpeed(SpeedOptions.values[which])
                        (this@MainActivity.root.findViewWithTag<Button>("speedButton"))?.text =
                            "播放速度：${SpeedOptions.labels[SpeedOptions.indexOf(speed)]}"
                        dialog.dismiss()
                    } catch (t: Exception) { error(t) }
                }
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.listView?.tag = "speedList"
                dialog.window?.decorView?.tag = "speedDialog"
            }
            dialog.show()
            dialog.listView?.tag = "speedList"
            dialog.window?.decorView?.tag = "speedDialog"
            }.apply { tag = "speedButton" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("演奏風格：${PerformanceStyle.labels[service.performanceStyle.ordinal]}") {
            AlertDialog.Builder(ui)
                .setTitle("演奏風格")
                .setSingleChoiceItems(PerformanceStyle.options, service.performanceStyle.ordinal) { dialog, which ->
                    try {
                        service.setPerformanceStyle(PerformanceStyle.entries[which])
                        (this@MainActivity.root.findViewWithTag<Button>("performanceStyleButton"))?.text =
                            "演奏風格：${PerformanceStyle.labels[service.performanceStyle.ordinal]}"
                        dialog.dismiss()
                    } catch (t: Exception) { error(t) }
                }
                .setNegativeButton("取消", null)
                .show()
            }.apply { tag = "performanceStyleButton" }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(text("樂器與聲部", 21f).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(text("聲部開關會在切換曲目後重設；替換樂器會依曲目保存。", 13f).apply { setTextColor(colors.muted) })
        root.addView(CheckBox(ui).apply { text = "節拍器（四分音符節拍）"; isChecked = metronome; tint(this); setOnCheckedChangeListener { _, checked -> metronome = checked } })
        s.parts.forEachIndexed { i, p -> root.addView(column().apply {
            setPadding(dp(12), dp(8), dp(12), dp(10)); background = rounded(colors.surface, 16, colors.line)
            val lamp = text("○ ${p.name}", 18f).apply { setTextColor(if (enabled()[i]) PartColors.color(i, colors.dark) else colors.muted) }; lamps += lamp; addView(lamp)
            addView(row().apply {
                addView(Switch(ui).apply {
                    text = "啟用聲部"; isChecked = !muted[i]; tint(this)
                    setOnCheckedChangeListener { _, checked -> if (muted[i] == checked) {
                        muted[i] = !checked; service.remix(); refreshPlayerState()
                    } }
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(button(InstrumentNames.name(programs[i], p.percussion)) {
                    if (!p.percussion) AlertDialog.Builder(ui).setTitle("替換 ${p.name} 的音色").setItems(InstrumentNames.choices.map { it.second }.toTypedArray()) { _, choice ->
                        service.setProgram(i, InstrumentNames.choices[choice].first); refreshPlayerState()
                    }.show()
                 }.apply {
                     setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                 }.also { instrumentButtons += it })
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        refreshPartControls()
        root.addView(text("採樣音源：GeneralUser GS 2.0.3 · TinySoundFont。音源隨 App 安裝，可全離線播放。", 12f))
        root.addView(button("音源引擎授權") {
            val licenses = listOf("GeneralUser-LICENSE.txt", "TinySoundFont-LICENSE.txt", "Oboe-LICENSE.txt").joinToString("\n\n") { name -> "$name\n${assets.open(name).bufferedReader().use { it.readText() }}" }
            AlertDialog.Builder(ui).setTitle("第三方授權").setMessage(licenses).setPositiveButton("關閉", null).show()
        })
    }
    private fun showMeasureDialog(s: Score) {
        AlertDialog.Builder(ui).setTitle("跳至小節")
            .setItems(s.bars.mapIndexed { i, bar -> "${i + 1}. 小節 ${bar.number}" }.toTypedArray()) { _, i -> service.seek(s.bars[i].tick) }
            .show()
    }
    private fun showPlayerMenu(anchor: View) {
        PopupMenu(ui, anchor).apply {
            menu.add(0, WAV_EXPORT_MENU_ITEM, 0, "匯出 WAV 音訊").setOnMenuItemClickListener {
                requestWavExport(); true
            }
        }.show()
    }
    private fun requestWavExport() {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, "${entry?.name}.wav")
            }, 11)
    }
    private fun listener(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) change(value) }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    }
    private fun updateTransportButton() {
        val active = service.wantsPlayback && when (service.transportPhase) {
            TransportPhase.PREPARING, TransportPhase.STARTING, TransportPhase.PLAYING, TransportPhase.RECONNECTING -> true
            else -> false
        }
        playButton?.apply {
            setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = transportLabel(); tooltipText = contentDescription
        }
    }
    private fun updateRepeatButton() {
        repeatButton?.apply {
            isActivated = service.repeatMode != RepeatMode.OFF; isSelected = isActivated
            imageTintList = ColorStateList.valueOf(if (isActivated) colors.accent else colors.ink)
            contentDescription = when (service.repeatMode) {
                RepeatMode.OFF -> "開啟清單循環"
                RepeatMode.ALL -> "清單循環，切換為單曲循環"
                RepeatMode.ONE -> "單曲循環，關閉循環"
            }
            tooltipText = contentDescription
        }
    }
    private fun updateShuffleButton() {
        shuffleButton?.apply {
            isActivated = service.shuffleEnabled; isSelected = service.shuffleEnabled
            imageTintList = ColorStateList.valueOf(if (service.shuffleEnabled) colors.accent else colors.ink)
            contentDescription = if (service.shuffleEnabled) "關閉隨機播放" else "開啟隨機播放"
            tooltipText = contentDescription
        }
    }
    private fun updateMiniTransportButton() {
        val p = playback ?: return
        val active = p.wantsPlayback && p.transportPhase in setOf(
            TransportPhase.PREPARING, TransportPhase.STARTING, TransportPhase.PLAYING, TransportPhase.RECONNECTING)
        miniButton?.apply {
            setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = transportLabel(); tooltipText = contentDescription
            background = circle(colors.accent)
            imageTintList = ColorStateList.valueOf(colors.onAccent)
        }
    }
    private fun userTogglePlayback() {
        val active = service.wantsPlayback && service.transportPhase in setOf(
            TransportPhase.PREPARING, TransportPhase.STARTING, TransportPhase.PLAYING, TransportPhase.RECONNECTING)
        if (active) service.toggle() else explicitPlayback { service.toggle() }
    }
    private fun explicitPlayback(action: () -> Unit) {
        action()
        requestNotificationPermission()
    }
    private fun requestNotificationPermission() {
        val preferences = getSharedPreferences("notification_permission", MODE_PRIVATE)
        NotificationPermissionPrompt.runAfterPlayback(
            Build.VERSION.SDK_INT,
            Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            preferences.getBoolean("requested", false),
            {},
            {
                if (!notificationRequestPending) {
                    notificationRequestPending = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
                }
            }
        )
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            notificationRequestPending = false
            if (grantResults.isNotEmpty()) getSharedPreferences("notification_permission", MODE_PRIVATE)
                .edit().putBoolean("requested", true).apply()
        }
    }
    @Deprecated("Activity back")
    override fun onBackPressed() {
        when (page) {
            "player" -> returnFromPlayer()
            "settings" -> showHome()
            "tracks", "playlists" -> showHome()
            "playlist" -> showPlaylists()
            else -> super.onBackPressed()
        }
    }
    private fun returnFromPlayer() {
        when (playerReturnPage) {
            "settings" -> showSettings()
            "tracks" -> showTracks()
            "playlists" -> showPlaylists()
            "playlist" -> showPlaylist(playlistId)
            else -> showHome()
        }
    }
    override fun onStart() { super.onStart(); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onStop() { handler.removeCallbacks(ticker); super.onStop() }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause() }
    override fun onDestroy() {
        alive = false; exportGeneration++; exportTask?.cancel(true)
        handler.removeCallbacksAndMessages(null); worker.shutdownNow(); exportWorker.shutdownNow()
        playback?.trackChangedListener = null
        if (bound) unbindService(connection)
        super.onDestroy()
    }
    private fun time(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    private fun transportLabel() = when (service.transportPhase) {
        TransportPhase.COMPLETED -> "重新播放"
        TransportPhase.ROUTE_UNAVAILABLE -> "重試"
        TransportPhase.PREPARING, TransportPhase.STARTING, TransportPhase.PLAYING, TransportPhase.RECONNECTING ->
            if (service.wantsPlayback) "暫停" else "播放"
        TransportPhase.PAUSED, TransportPhase.ERROR -> "播放"
    }
    private fun TextView.setTextIfChanged(value: CharSequence) { if (text.toString() != value.toString()) text = value }
    companion object {
        private const val WAV_EXPORT_MENU_ITEM = 1
        private const val NOTIFICATION_PERMISSION_REQUEST = 20
    }
}
