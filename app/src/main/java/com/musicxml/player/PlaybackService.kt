package com.musicxml.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors

enum class TransportPhase { PREPARING, STARTING, PLAYING, RECONNECTING, ROUTE_UNAVAILABLE, PAUSED, COMPLETED, ERROR }

internal data class OutputDevice(val id: Int, val type: Int, val name: String = "")

internal data class WavExportSnapshot(
    val score: Score,
    val includeMetronome: Boolean,
    val style: PerformanceStyle,
    val enabled: List<Boolean>,
    val volumes: List<Int>,
    val programs: List<Int>,
    val overrides: List<Boolean>
)

internal object AudioRouteDisplay {
    fun label(actualId: Int, outputs: List<OutputDevice>, inferred: List<OutputDevice> = emptyList()): String {
        val actual = if (actualId > 0) outputs.firstOrNull { it.id == actualId } else null
        if (actual != null) return "音訊輸出：${deviceLabel(actual)}"
        if (actualId <= 0 && inferred.isNotEmpty()) return "音訊輸出：${deviceLabel(inferred.first())}（系統推定）"
        return "音訊輸出：系統選擇"
    }

    fun shouldPauseForRemoval(active: Boolean, actualId: Int, removedIds: Collection<Int>) =
        active && actualId > 0 && actualId in removedIds

    private fun deviceLabel(device: OutputDevice): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "裝置喇叭"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "聽筒"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有線耳機"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "藍牙音訊"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> "藍牙音訊"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音訊"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
            AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "外接音訊"
            else -> "其他裝置"
        }
        val name = device.name.trim()
        return if (name.isBlank() || name.equals(kind, true)) kind else "$kind（$name）"
    }
}

/** The service owns audio and transport state; screens are only views of this session. */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@PlaybackService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val renderer = Executors.newSingleThreadExecutor()
    private lateinit var session: MediaSession
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val notifications by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:realtime").apply { setReferenceCounted(false) }
    }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    if (resumeAfterFocusLoss) { resumeAfterFocusLoss = false; play() }
                } else {
                    val resume = (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) && wantsPlayback
                    pause(abandonFocus = change == AudioManager.AUDIOFOCUS_LOSS, clearFocusResume = false)
                    resumeAfterFocusLoss = resume
                }
            }.build()
    }
    private var resumeAfterFocusLoss = false
    private var foreground = false
    @Volatile private var generation = 0
    @Volatile private var alive = true
    private var savedPosition = 0
    private var lastSessionSavedAt = 0L
    private var notificationTicks = 0
    private var routeDeviceId = Int.MIN_VALUE
    private var routeLabel = "音訊輸出：系統選擇"
    private var routeResolvedAt = 0L
    private var suppressFinished = false
    var player: RealtimePlayer? = null
        private set
    var trackChangedListener: (() -> Unit)? = null
    var rendering = false
        private set
    var wantsPlayback = false
        private set
    var errorMessage: String? = null
    var realtimeStatus: RealtimeStatus? = null
        private set
    var transportPhase = TransportPhase.PAUSED
        private set
    var completed = false
        private set
    val repeatOne get() = repeatMode == RepeatMode.ONE
    var repeatMode = RepeatMode.OFF
        private set
    var shuffleEnabled = false
        private set
    val queueIds = mutableListOf<String>()
    val playOrderIds = mutableListOf<String>()
    var score: Score? = null
        private set
    var entry: LibraryEntry? = null
        private set
    var muted = mutableListOf<Boolean>()
    var solo = -1
    var volumes = mutableListOf<Int>()
    var programs = mutableListOf<Int>()
    var programOverrides = mutableListOf<Boolean>()
        private set
    private var observedPrograms = emptyList<Int>()
    var speed = 1f
        private set
    var performanceStyle = PerformanceStyle.ORIGINAL
        private set
    var metronome = false
        set(value) { field = value; remix() }
    val currentPosition get() = realtimeStatus?.positionMillis ?: savedPosition
    val isPlaying get() = transportPhase == TransportPhase.PLAYING
    val currentQueueIndex get() = entry?.id?.let(playOrderIds::indexOf) ?: -1
    val hasPrevious get() = currentQueueIndex > 0 || (repeatMode == RepeatMode.ALL && playOrderIds.isNotEmpty())
    val hasNext get() = currentQueueIndex in 0 until playOrderIds.lastIndex || (repeatMode == RepeatMode.ALL && playOrderIds.isNotEmpty())
    val audioRouteLabel: String get() {
        val actualId = realtimeStatus?.deviceId ?: 0
        val now = SystemClock.elapsedRealtime()
        if (routeDeviceId == actualId && (actualId > 0 || now - routeResolvedAt < 1000)) return routeLabel
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::outputDevice)
        val inferred = if (actualId <= 0 && Build.VERSION.SDK_INT >= 33)
            audio.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).map(::outputDevice)
        else emptyList()
        routeDeviceId = actualId
        routeResolvedAt = now
        routeLabel = AudioRouteDisplay.label(actualId, outputs, inferred)
        return routeLabel
    }

    // Only stream/font preparation is asynchronous. Live controls never reload the font.
    internal var preparePlayer: (Score) -> RealtimePlayer = { NativeRealtimePlayer(applicationContext, PerformanceCompiler.compile(it, performanceStyle)) }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            routeDeviceId = Int.MIN_VALUE; scheduleRouteRetry()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val actualId = runCatching { player?.status?.deviceId }.getOrNull() ?: realtimeStatus?.deviceId ?: 0
            if (AudioRouteDisplay.shouldPauseForRemoval(wantsPlayback, actualId,
                    removedDevices.orEmpty().map { it.id })) pause()
            routeDeviceId = Int.MIN_VALUE
            scheduleRouteRetry()
        }
    }
    private val retryRoute = Runnable {
        val current = player ?: return@Runnable
        try { current.retryAudioRoute() } catch (t: Exception) { fail(t.message ?: "無法重新連接音訊裝置") }
    }
    private val transport = object : Runnable {
        override fun run() {
            pollTransport()
            if (++notificationTicks % 20 == 0 && (isPlaying || transportPhase == TransportPhase.RECONNECTING)) updateSession()
            handler.postDelayed(this, 50)
        }
    }
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "音樂播放", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "MusicXMLPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seekMillis(pos.toInt()) }
                override fun onSkipToPrevious() { skipPrevious() }
                override fun onSkipToNext() { skipNext() }
                override fun onCustomAction(action: String, extras: Bundle?) { if (action == ACTION_STOP) stop() }
            })
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisyReceiver, filter)
        audio.registerAudioDeviceCallback(audioDevices, handler)
        handler.post(transport)
        val library = Library(this)
        val persistence = PlaybackPersistence(this)
        speed = persistence.speed(library)
        performanceStyle = persistence.style(library)
        persistence.loadSession(library.entries.mapTo(hashSetOf()) { it.id })?.let { snapshot ->
            queueIds += snapshot.queueIds
            playOrderIds += snapshot.playOrderIds
            shuffleEnabled = snapshot.shuffle
            repeatMode = snapshot.repeatMode
            loadTrack(snapshot.currentId, snapshot.positionMillis, autoplay = false, markPlayed = false)
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification intents may start an idle service: meet the foreground deadline first.
        promote()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> { stop(); return START_NOT_STICKY }
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_NEXT -> skipNext()
        }
        if (score != null && !isPlaying && !rendering && !wantsPlayback) pause()
        if (score == null && !rendering) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf() }
        return START_NOT_STICKY
    }
    fun open(e: LibraryEntry, parsed: Score) {
        if (entry?.id == e.id && score != null) { play(); return }
        queueIds.clear(); queueIds += e.id
        playOrderIds.clear(); playOrderIds += e.id
        shuffleEnabled = false
        activateParsed(e, parsed, 0, autoplay = true, markPlayed = true)
    }

    fun playQueue(ids: List<String>, startId: String, shuffle: Boolean = false) {
        val valid = Library(this).entries.mapTo(hashSetOf()) { it.id }
        val normalized = ids.filter { it in valid }.distinct()
        require(startId in normalized) { "找不到要播放的曲目" }
        queueIds.clear(); queueIds += normalized
        shuffleEnabled = shuffle
        rebuildPlayOrder(startId)
        loadTrack(startId, 0, autoplay = true, markPlayed = true)
    }

    private fun activateParsed(e: LibraryEntry, parsed: Score, position: Int, autoplay: Boolean, markPlayed: Boolean) {
        clearAudio()
        score = parsed; entry = e; solo = -1
        savedPosition = position.coerceIn(0, parsed.millisAt(parsed.endTick)); errorMessage = null; completed = false; suppressFinished = false
        muted = parsed.parts.map { false }.toMutableList()
        volumes = parsed.parts.mapIndexed { i, _ -> VolumeOptions.normalize(e.volumes.getOrNull(i) ?: VolumeOptions.DEFAULT) }.toMutableList()
        programs = parsed.parts.mapIndexed { i, p -> e.programs.getOrNull(i) ?: p.program }.toMutableList()
        programOverrides = parsed.parts.mapIndexed { i, p -> e.programOverrides.getOrNull(i) ?: (programs[i] != p.program) }.toMutableList()
        observedPrograms = programs.toList()
        val persistence = PlaybackPersistence(this)
        val library = Library(this)
        speed = persistence.speed(library)
        performanceStyle = persistence.style(library)
        trackChangedListener?.invoke()
        if (markPlayed) {
            e.lastPlayed = System.currentTimeMillis()
            saveSettings()
        }
        prepare(savedPosition, autoplay)
        saveSession(force = true)
    }

    private fun loadTrack(id: String, position: Int, autoplay: Boolean, markPlayed: Boolean) {
        val library = Library(this)
        val target = library.entries.firstOrNull { it.id == id } ?: run {
            removeMissingQueueId(id)
            return
        }
        clearAudio()
        entry = target; score = null; savedPosition = position.coerceAtLeast(0)
        rendering = true; wantsPlayback = autoplay; transportPhase = TransportPhase.PREPARING
        errorMessage = null; completed = false
        val currentGeneration = generation
        if (autoplay) { ensureRunning(); holdWakeLock() }
        updateSession()
        renderer.execute {
            try {
                val parsed = MusicXml.parse(library.file(target).readBytes())
                handler.post {
                    if (alive && currentGeneration == generation) {
                        val shouldAutoplay = wantsPlayback
                        activateParsed(target, parsed, position, shouldAutoplay, markPlayed)
                    }
                }
            } catch (t: Throwable) {
                handler.post { if (alive && currentGeneration == generation) fail(t.message ?: "無法載入曲目") }
            }
        }
    }

    private fun removeMissingQueueId(id: String) {
        queueIds.removeAll { it == id }; playOrderIds.removeAll { it == id }
        if (queueIds.isEmpty()) removeCurrent() else saveSession(force = true)
    }

    private fun rebuildPlayOrder(currentId: String) {
        playOrderIds.clear()
        if (shuffleEnabled) {
            playOrderIds += currentId
            playOrderIds += queueIds.filter { it != currentId }.shuffled()
        } else playOrderIds += queueIds
    }

    fun setShuffle(enabled: Boolean) {
        if (shuffleEnabled == enabled || queueIds.isEmpty()) return
        val current = entry?.id ?: queueIds.first()
        shuffleEnabled = enabled
        rebuildPlayOrder(current)
        saveSession(force = true); updateSession()
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        saveSession(force = true); updateSession()
    }

    fun skipNext(): Boolean = moveQueue(1)

    fun skipPrevious(): Boolean {
        if (currentPosition > 3000) { seekMillis(0); return true }
        return moveQueue(-1)
    }

    private fun moveQueue(direction: Int): Boolean {
        val index = currentQueueIndex
        if (index < 0 || playOrderIds.isEmpty()) return false
        var target = index + direction
        if (target !in playOrderIds.indices) {
            if (repeatMode == RepeatMode.ALL && playOrderIds.isNotEmpty()) target = if (direction > 0) 0 else playOrderIds.lastIndex
            else return false
        }
        loadTrack(playOrderIds[target], 0, autoplay = true, markPlayed = true)
        return true
    }
    fun enabled() = muted.indices.map { !muted[it] && (solo == -1 || solo == it) }
    internal fun exportSnapshot(): WavExportSnapshot {
        val s = score ?: error("沒有播放中的樂譜")
        val includeMetronome = metronome
        return WavExportSnapshot(s, includeMetronome, performanceStyle,
            enabled() + if (includeMetronome) listOf(true) else emptyList(),
            volumes.toList() + if (includeMetronome) listOf(80) else emptyList(),
            programs.toList() + if (includeMetronome) listOf(0) else emptyList(),
            programOverrides.toList() + if (includeMetronome) listOf(false) else emptyList())
    }
    fun saveSettings() {
        val current = entry ?: return
        current.volumes = volumes.toList(); current.programs = programs.toList()
        current.programOverrides = programOverrides.toList()
        // Reload the library so importing/renaming while listening cannot be overwritten.
        val library = Library(this)
        library.entries.firstOrNull { it.id == current.id }?.let {
            it.volumes = volumes.toList(); it.programs = programs.toList()
            it.programOverrides = programOverrides.toList(); it.lastPlayed = current.lastPlayed
            current.name = it.name
        }
        library.save()
        PlaybackPersistence(this).saveGlobals(speed, performanceStyle)
    }
    fun setSpeed(value: Float) {
        speed = normalizeSpeed(value)
        player?.setSpeed(speed)
        PlaybackPersistence(this).saveGlobals(speed, performanceStyle)
        updateSession()
    }
    fun setPerformanceStyle(value: PerformanceStyle) {
        if (performanceStyle == value) return
        performanceStyle = value
        PlaybackPersistence(this).saveGlobals(speed, performanceStyle)
        if (score != null) prepare(currentPosition, wantsPlayback)
        updateSession()
    }
    private fun normalizeSpeed(value: Float) = SpeedOptions.normalize(value)
    fun setProgram(part: Int, program: Int) {
        programs[part] = program.coerceIn(0, 127)
        programOverrides[part] = true
        observedPrograms = programs.toList()
        saveSettings(); remix()
    }
    fun remix() {
        try {
            programs.indices.forEach { i ->
                if (programs[i] != observedPrograms.getOrNull(i)) programOverrides[i] = true
            }
            observedPrograms = programs.toList()
            player?.setMix(enabled() + metronome, volumes.toList() + 80, programs.toList() + 0, programOverrides.toList() + false)
        } catch (t: Exception) { fail(t.message ?: "無法更新即時混音") }
    }
    private fun prepare(position: Int, autoplay: Boolean) {
        val s = (score ?: return).withMetronome()
        clearAudio()
        savedPosition = position; wantsPlayback = autoplay; rendering = true; completed = false; suppressFinished = false
        transportPhase = TransportPhase.PREPARING
        errorMessage = null
        val currentGeneration = generation
        if (autoplay) { ensureRunning(); holdWakeLock() }
        updateSession()
        renderer.execute {
            if (!alive || currentGeneration != generation) return@execute
            try {
                val prepared = preparePlayer(s)
                if (!alive || currentGeneration != generation) { prepared.close(); return@execute }
                handler.post {
                    if (!alive || currentGeneration != generation) { prepared.close(); return@post }
                    try {
                        player = prepared
                        prepared.setSpeed(speed); remix(); prepared.seekMillis(savedPosition)
                        realtimeStatus = prepared.status
                        rendering = false
                        if (wantsPlayback) play() else pause(abandonFocus = false, clearFocusResume = false)
                    } catch (t: Exception) { fail(t.message ?: "無法開啟音訊") }
                }
            } catch (t: Throwable) {
                handler.post { if (alive && currentGeneration == generation) fail(t.message ?: "無法準備即時音訊") }
            }
        }
    }
    fun seek(tick: Int) { score?.let { seekMillis(it.millisAt(tick.coerceIn(0, it.endTick))) } }
    private fun seekMillis(position: Int) {
        completed = false
        suppressFinished = true
        savedPosition = position.coerceIn(0, score?.let { it.millisAt(it.endTick) } ?: 0)
        try {
            player?.seekMillis(savedPosition)
            realtimeStatus = realtimeStatus?.copy(positionMillis = savedPosition)
            if (!rendering && transportPhase == TransportPhase.COMPLETED) transportPhase = TransportPhase.PAUSED
        } catch (t: Exception) { fail(t.message ?: "無法跳至指定位置"); return }
        saveSession(force = true); updateSession()
    }
    fun toggle() { if (wantsPlayback && transportPhase in ACTIVE_PHASES) pause() else play() }
    fun toggleRepeatOne() {
        repeatMode = if (repeatMode == RepeatMode.ONE) RepeatMode.OFF else RepeatMode.ONE
        saveSession(force = true); updateSession()
    }
    fun play() {
        if (score == null) {
            if (rendering || entry != null) {
                wantsPlayback = true; resumeAfterFocusLoss = false
                ensureRunning(); holdWakeLock(); updateSession()
            }
            return
        }
        val replay = completed
        completed = false; suppressFinished = false; wantsPlayback = true; resumeAfterFocusLoss = false; errorMessage = null
        ensureRunning()
        if (rendering) { updateSession(); return }
        if (player == null) { prepare(savedPosition, true); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pause(); return
        }
        try {
            holdWakeLock()
            if (replay) {
                savedPosition = 0
                player?.seekMillis(0)
                realtimeStatus = realtimeStatus?.copy(positionMillis = 0)
            }
            if (transportPhase == TransportPhase.ROUTE_UNAVAILABLE) player?.retryAudioRoute()
            player?.play()
            refreshStatus()
            updateSession()
        } catch (t: Exception) { fail(t.message ?: "無法播放") }
    }
    fun pause(abandonFocus: Boolean = true, clearFocusResume: Boolean = true) {
        wantsPlayback = false
        if (clearFocusResume) resumeAfterFocusLoss = false
        try { player?.pause() } catch (t: Exception) { fail(t.message ?: "無法暫停"); return }
        if (!completed && !rendering) transportPhase = TransportPhase.PAUSED
        if (!rendering) releaseWakeLock()
        if (abandonFocus) audio.abandonAudioFocusRequest(focus)
        if (foreground && !rendering && abandonFocus) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
        saveSession(force = true); updateSession()
    }
    fun stop() {
        completed = false; suppressFinished = true; pause(); generation++; rendering = false; savedPosition = 0
        try { player?.seekMillis(0); realtimeStatus = realtimeStatus?.copy(positionMillis = 0) }
        catch (t: Exception) { fail(t.message ?: "無法停止播放"); return }
        transportPhase = TransportPhase.PAUSED; releaseWakeLock()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
        saveSession(force = true); notifications.cancel(NOTIFICATION); stopSelf()
    }
    fun removeCurrent() {
        stop(); clearAudio(); entry = null; score = null
        queueIds.clear(); playOrderIds.clear()
        PlaybackPersistence(this).saveSession(null)
    }
    fun removeTrack(id: String) {
        if (entry?.id == id) {
            val wasPlaying = wantsPlayback
            val index = currentQueueIndex
            queueIds.removeAll { it == id }; playOrderIds.removeAll { it == id }
            val replacement = playOrderIds.getOrNull(index.coerceAtMost(playOrderIds.lastIndex))
            if (replacement == null) removeCurrent() else loadTrack(replacement, 0, autoplay = wasPlaying, markPlayed = wasPlaying)
            return
        }
        queueIds.removeAll { it == id }; playOrderIds.removeAll { it == id }
        saveSession(force = true); updateSession()
    }
    private fun clearAudio() {
        generation++; rendering = false; wantsPlayback = false
        player?.close(); player = null; realtimeStatus = null
        audio.abandonAudioFocusRequest(focus); releaseWakeLock()
    }
    private fun holdWakeLock() { if (!wakeLock.isHeld) wakeLock.acquire() }
    private fun releaseWakeLock() { if (wakeLock.isHeld) wakeLock.release() }
    private fun fail(message: String) {
        completed = false; suppressFinished = false; clearAudio(); errorMessage = message; transportPhase = TransportPhase.ERROR
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
        notifications.cancel(NOTIFICATION); updateSession(); stopSelf()
    }
    private fun ensureRunning() {
        if (!foreground) {
            startForegroundService(Intent(this, PlaybackService::class.java))
            promote()
        }
    }
    private fun promote() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification)
        foreground = true
    }
    private fun scheduleRouteRetry() {
        if (player == null) return
        handler.removeCallbacks(retryRoute)
        handler.postDelayed(retryRoute, 150)
    }
    private fun outputDevice(device: AudioDeviceInfo) = OutputDevice(device.id, device.type, device.productName?.toString().orEmpty())
    private fun refreshStatus(): RealtimeStatus? {
        val current = player ?: return null
        val status = current.status
        realtimeStatus = status
        savedPosition = status.positionMillis
        transportPhase = when (status.phase) {
            RealtimePhase.STARTING -> if (wantsPlayback) TransportPhase.STARTING else TransportPhase.PAUSED
            RealtimePhase.PLAYING -> if (wantsPlayback) TransportPhase.PLAYING else TransportPhase.PAUSED
            RealtimePhase.RECONNECTING -> if (wantsPlayback) TransportPhase.RECONNECTING else TransportPhase.PAUSED
            RealtimePhase.ROUTE_UNAVAILABLE -> TransportPhase.ROUTE_UNAVAILABLE
            RealtimePhase.FINISHED -> if (!completed && suppressFinished) TransportPhase.PAUSED else TransportPhase.COMPLETED
            RealtimePhase.ERROR, RealtimePhase.CLOSED -> TransportPhase.ERROR
            RealtimePhase.PAUSED -> if (completed) TransportPhase.COMPLETED else TransportPhase.PAUSED
        }
        return status
    }
    private fun pollTransport() {
        if (rendering || player == null) return
        val before = transportPhase
        val status = try { refreshStatus() } catch (t: Exception) {
            fail(t.message ?: "無法讀取音訊狀態"); return
        } ?: return
        when (status.phase) {
            RealtimePhase.ERROR, RealtimePhase.CLOSED -> {
                val message = try { player?.errorMessage } catch (_: Exception) { null }
                fail(message ?: "即時音訊裝置錯誤（${status.errorCode}）")
                return
            }
            RealtimePhase.FINISHED -> if (!completed && !suppressFinished) completePlayback()
            RealtimePhase.ROUTE_UNAVAILABLE -> if (wantsPlayback || before != TransportPhase.ROUTE_UNAVAILABLE) routeUnavailable()
            RealtimePhase.STARTING, RealtimePhase.RECONNECTING -> if (wantsPlayback) {
                ensureRunning(); holdWakeLock()
            }
            else -> suppressFinished = false
        }
        if (before != transportPhase) updateSession()
        if (isPlaying) saveSession()
    }
    private fun completePlayback() {
        if (repeatMode == RepeatMode.ONE && (wantsPlayback || resumeAfterFocusLoss)) {
            suppressFinished = true; savedPosition = 0
            try {
                player?.seekMillis(0); realtimeStatus = realtimeStatus?.copy(positionMillis = 0)
                if (wantsPlayback) {
                    player?.play(); refreshStatus(); suppressFinished = false
                } else transportPhase = TransportPhase.PAUSED
            } catch (t: Exception) { fail(t.message ?: "無法循環播放"); return }
            updateSession(); return
        }
        if (wantsPlayback && moveQueue(1)) return
        finishQueue()
    }

    private fun finishQueue() {
        completed = true; suppressFinished = true; wantsPlayback = false; resumeAfterFocusLoss = false; savedPosition = 0
        try { player?.seekMillis(0); realtimeStatus = realtimeStatus?.copy(positionMillis = 0) }
        catch (t: Exception) { fail(t.message ?: "無法重設播放位置"); return }
        transportPhase = TransportPhase.COMPLETED
        audio.abandonAudioFocusRequest(focus); releaseWakeLock()
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
        saveSession(force = true); updateSession()
    }
    private fun routeUnavailable() {
        try { player?.pause() } catch (t: Exception) { fail(t.message ?: "無法安全暫停"); return }
        wantsPlayback = false; resumeAfterFocusLoss = false; transportPhase = TransportPhase.ROUTE_UNAVAILABLE
        audio.abandonAudioFocusRequest(focus); releaseWakeLock()
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
        updateSession()
    }
    private fun updateSession() {
        if (!::session.isInitialized) return
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, entry?.name ?: getString(R.string.app_name))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, score?.composer.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, score?.let { it.millisAt(it.endTick).toLong() } ?: 0).build())
        val state = when (transportPhase) {
            TransportPhase.PREPARING, TransportPhase.STARTING, TransportPhase.RECONNECTING -> PlaybackState.STATE_BUFFERING
            TransportPhase.PLAYING -> PlaybackState.STATE_PLAYING
            TransportPhase.COMPLETED -> PlaybackState.STATE_STOPPED
            TransportPhase.ERROR -> PlaybackState.STATE_ERROR
            TransportPhase.ROUTE_UNAVAILABLE, TransportPhase.PAUSED -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
        if (hasPrevious || currentPosition > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        val stateBuilder = PlaybackState.Builder()
            .setActions(actions)
            .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_STOP, "停止", android.R.drawable.ic_menu_close_clear_cancel).build())
            .setState(state, currentPosition.toLong(), if (isPlaying) speed else 0f)
        if (transportPhase == TransportPhase.ERROR) stateBuilder.setErrorMessage(errorMessage ?: "播放錯誤")
        else if (transportPhase == TransportPhase.ROUTE_UNAVAILABLE) stateBuilder.setErrorMessage("找不到可用的音訊輸出，請連接裝置後重試")
        session.setPlaybackState(stateBuilder.build())
        session.isActive = score != null
        if (score != null && (foreground || isPlaying || player != null || transportPhase == TransportPhase.ERROR))
            notifications.notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        fun action(name: String, request: Int) = PendingIntent.getForegroundService(this, request,
            Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = wantsPlayback && transportPhase in ACTIVE_PHASES
        val description = when (transportPhase) {
            TransportPhase.PREPARING -> "正在載入採樣音源…"
            TransportPhase.STARTING -> "正在啟動播放…"
            TransportPhase.PLAYING -> "正在播放"
            TransportPhase.RECONNECTING -> "正在重新連接音訊裝置…"
            TransportPhase.ROUTE_UNAVAILABLE -> "找不到音訊輸出，點按重試"
            TransportPhase.COMPLETED -> "播放完畢"
            TransportPhase.ERROR -> errorMessage ?: "播放錯誤"
            TransportPhase.PAUSED -> "已暫停"
        }
        val actionLabel = when (transportPhase) {
            TransportPhase.COMPLETED -> "重新播放"
            TransportPhase.ROUTE_UNAVAILABLE -> "重試"
            else -> if (active) "暫停" else "播放"
        }
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(entry?.name ?: getString(R.string.app_name))
            .setContentText(description)
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(active)
        if (hasPrevious || currentPosition > 0) builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_media_previous, "上一首", action(ACTION_PREVIOUS, 3)).build())
        builder
            .addAction(Notification.Action.Builder(if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                actionLabel, action(if (active) ACTION_PAUSE else ACTION_PLAY, 1)).build())
        if (hasNext) builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_media_next, "下一首", action(ACTION_NEXT, 4)).build())
        builder
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 2)).build())
        val compact = when {
            hasPrevious && hasNext -> intArrayOf(0, 1, 2)
            hasPrevious || hasNext -> intArrayOf(0, 1)
            else -> intArrayOf(0)
        }
        return builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact)).build()
    }
    override fun onDestroy() {
        saveSession(force = true)
        trackChangedListener = null
        alive = false; clearAudio(); handler.removeCallbacksAndMessages(null); renderer.shutdownNow()
        unregisterReceiver(noisyReceiver); audio.unregisterAudioDeviceCallback(audioDevices)
        session.release(); notifications.cancel(NOTIFICATION)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        private val MEDIA_ATTRIBUTES = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        const val ACTION_PLAY = "com.musicxml.player.PLAY"
        const val ACTION_PAUSE = "com.musicxml.player.PAUSE"
        const val ACTION_STOP = "com.musicxml.player.STOP"
        const val ACTION_PREVIOUS = "com.musicxml.player.PREVIOUS"
        const val ACTION_NEXT = "com.musicxml.player.NEXT"
        const val OPEN_PLAYER = "com.musicxml.player.OPEN_PLAYER"
        private val ACTIVE_PHASES = setOf(TransportPhase.PREPARING, TransportPhase.STARTING, TransportPhase.PLAYING, TransportPhase.RECONNECTING)
    }

    private fun saveSession(force: Boolean = false) {
        val current = entry?.id ?: return
        if (current !in queueIds) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSessionSavedAt < 1000) return
        lastSessionSavedAt = now
        PlaybackPersistence(this).saveSession(PlaybackSessionSnapshot(
            queueIds.toList(), playOrderIds.toList(), current, currentPosition,
            shuffleEnabled, repeatMode
        ))
    }
}
