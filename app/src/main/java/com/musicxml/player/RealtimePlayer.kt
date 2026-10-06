package com.musicxml.player

import android.content.Context
import java.io.File

interface RealtimePlayer : AutoCloseable {
    val currentPosition: Int
    val isPlaying: Boolean
    val isFinished: Boolean
    val errorMessage: String?
    val status: RealtimeStatus
    fun play()
    fun pause()
    fun seekMillis(position: Int)
    fun setSpeed(speed: Float)
    fun setMix(enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, overrides: List<Boolean>)
    fun retryAudioRoute()
}

enum class RealtimePhase { PAUSED, STARTING, PLAYING, RECONNECTING, ROUTE_UNAVAILABLE, FINISHED, ERROR, CLOSED }
data class RealtimeStatus(
    val phase: RealtimePhase,
    val positionMillis: Int,
    val errorCode: Int,
    val xrunCount: Int,
    val bufferFrames: Int,
    val framesPerBurst: Int,
    val bufferCapacityFrames: Int,
    val callbacksOverBudget: Long,
    val maxCallbackMicros: Long,
    val reconnectAttempts: Int,
    val deviceId: Int,
    val audioApi: Int
)

object SoundBank {
    @Synchronized fun ensure(context: Context): File {
        val bank = File(context.filesDir, "GeneralUser-GS.sf2")
        if (bank.length() != 32319396L) {
            val temporary = File(context.filesDir, "GeneralUser-GS.tmp")
            context.assets.open("GeneralUser-GS.sf2").use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.renameTo(bank)) { "無法安裝採樣音源" }
        }
        return bank
    }
}

/** Appends the click as the final logical part; source MIDI channels are intentionally ignored. */
fun Score.withMetronome(): Score {
    if (parts.any { it.id == APP_METRONOME_ID }) return this
    require(parts.size <= 15) { "即時引擎最多支援 15 個樂譜聲部及節拍器" }
    val channel = parts.size
    val clicks = bars.flatMap { bar -> (0 until bar.duration step PPQ).map { offset ->
        Note(bar.tick + offset, 60, if (offset == 0) 76 else 77, 85, parts.size,
            program = 0, bank = 128, channel = channel, percussion = true)
    } }
    return copy(parts = parts + Part(APP_METRONOME_ID, "節拍器", 0, true, 128, channel = channel), notes = notes + clicks)
}

internal const val APP_METRONOME_ID = "__musicxml_player_metronome__"

internal data class RoutedControl(val control: Control, val channel: Int)
internal data class NativeMix(val enabled: BooleanArray, val volumes: IntArray, val programs: IntArray,
                              val overrides: BooleanArray)
internal class AudioRouting(private val score: Score) {
    init {
        require(score.parts.size <= 15 || score.parts.size == 16 && score.parts.last().id == APP_METRONOME_ID) {
            "即時引擎最多支援 15 個樂譜聲部及節拍器"
        }
    }
    fun noteChannel(note: Note) = note.part
    val controls = score.controls.map { control -> RoutedControl(control, control.part) }
    // Retained for diagnostics; percussion selection itself is carried on every note.
    val drums = IntArray(16).also { values -> score.notes.forEach { if (it.percussion) values[it.part] = 1 } }
    val basePrograms = IntArray(16)
    val baseBanks = IntArray(16)
    init {
        score.parts.forEachIndexed { part, definition ->
            basePrograms[part] = definition.program
            baseBanks[part] = definition.bank
        }
    }
    fun mix(enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, overrides: List<Boolean>): NativeMix {
        val channelEnabled = BooleanArray(16)
        val channelVolumes = IntArray(16)
        val channelPrograms = basePrograms.copyOf()
        val channelOverrides = BooleanArray(16)
        score.parts.indices.forEach { part ->
            channelEnabled[part] = enabled.getOrElse(part) { true }
            channelVolumes[part] = volumes.getOrElse(part) { 100 }
            if (overrides.getOrElse(part) { false }) {
                channelPrograms[part] = programs.getOrElse(part) { score.parts[part].program }
                channelOverrides[part] = true
            }
        }
        return NativeMix(channelEnabled, channelVolumes, channelPrograms, channelOverrides)
    }
}

class NativeRealtimePlayer(context: Context, private val plan: PerformancePlan) : RealtimePlayer {
    constructor(context: Context, score: Score) : this(context, PerformanceCompiler.compile(score))
    private data class Selection(val enabled: BooleanArray, val volumes: IntArray, val programs: IntArray, val overrides: BooleanArray)
    companion object { init { System.loadLibrary("sampler") } }
    private var handle: Long
    private var pendingPosition: Int? = null
    private var selectedSpeed = 1f
    private var selectedMix: Selection? = null
    private val score = plan.source
    private val routing = AudioRouting(score)
    init {
        val duration = plan.durationMillis
        require(duration <= 30 * 60 * 1000) { "目前支援最長 30 分鐘的樂譜" }
        require(score.parts.size <= 16) { "即時引擎最多支援 16 個聲部（包含節拍器）" }
        val starts = plan.notes.map { it.startMillis }.toIntArray()
        val ends = plan.notes.map { it.endMillis }.toIntArray()
        val controlStarts = plan.controls.map { it.startMillis }.toIntArray()
        val controlEnds = plan.controls.map { it.endMillis }.toIntArray()
        val notePrograms = plan.notes.map { if (it.program >= 0) it.program else score.parts[it.part].program }.toIntArray()
        val noteBanks = plan.notes.map { if (it.bank >= 0) it.bank else score.parts[it.part].bank }.toIntArray()
        handle = create(SoundBank.ensure(context).absolutePath, starts, ends,
            plan.notes.map { routing.noteChannel(it.source) }.toIntArray(), plan.notes.map { it.pitch }.toIntArray(), plan.notes.map { it.velocity }.toIntArray(),
            notePrograms, noteBanks, score.notes.map { if (it.percussion) 1 else 0 }.toIntArray(), routing.basePrograms, routing.baseBanks, controlStarts, controlEnds,
            plan.controls.map { it.source.part }.toIntArray(), plan.controls.map { it.source.controller }.toIntArray(),
            plan.controls.map { it.source.from }.toIntArray(), plan.controls.map { it.source.to }.toIntArray(), duration)
        check(handle != 0L) { "無法建立即時採樣引擎" }
    }
    override val currentPosition get() = synchronized(this) {
        if (handle == 0L) 0 else plan.canonicalMillisAt(pendingPosition ?: state(handle, 0).toInt())
    }
    override val isPlaying get() = synchronized(this) { handle != 0L && state(handle, 1) != 0.0 }
    override val isFinished get() = synchronized(this) { handle != 0L && pendingPosition == null && state(handle, 2) != 0.0 }
    override val errorMessage get() = synchronized(this) {
        val error = if (handle == 0L) 0 else state(handle, 3).toInt()
        if (error == 0) null else "即時音訊裝置錯誤（$error）"
    }
    override val status get() = synchronized(this) {
        if (handle == 0L) return@synchronized RealtimeStatus(RealtimePhase.CLOSED, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val phases = RealtimePhase.entries
        val nativePhase = phases.getOrElse(state(handle, 4).toInt()) { RealtimePhase.ERROR }
        // The completion query waits for queued audio to be presented and publishes FINISHED.
        val phase = when {
            pendingPosition != null && nativePhase == RealtimePhase.FINISHED -> RealtimePhase.PAUSED
            pendingPosition == null && state(handle, 2) != 0.0 -> RealtimePhase.FINISHED
            else -> nativePhase
        }
        RealtimeStatus(phase, plan.canonicalMillisAt(pendingPosition ?: state(handle, 0).toInt()),
            state(handle, 3).toInt(), state(handle, 5).toInt(), state(handle, 6).toInt(), state(handle, 7).toInt(),
            state(handle, 8).toInt(), state(handle, 9).toLong(), state(handle, 10).toLong(), state(handle, 11).toInt(),
            state(handle, 12).toInt(), state(handle, 13).toInt())
    }
    @Synchronized private fun send(operation: Int, first: Double = 0.0, second: Double = 0.0) {
        if (handle != 0L) control(handle, operation, first, second)
    }
    @Synchronized override fun play() {
        if (handle == 0L) return
        // Mix and rate are latest-value mailboxes, so replay starts with the current selection.
        selectedMix?.let { configure(handle, it.programs, it.volumes, it.enabled, it.overrides) }
        control(handle, 3, selectedSpeed.toDouble(), 0.0)
        pendingPosition?.let { control(handle, 2, it.toDouble(), 0.0); pendingPosition = null }
        control(handle, 0, 0.0, 0.0)
    }
    override fun pause() = send(1)
    @Synchronized override fun seekMillis(position: Int) {
        if (handle == 0L) return
        val renderedPosition = plan.performanceMillisAt(position)
        when (state(handle, 4).toInt()) {
            RealtimePhase.PAUSED.ordinal, RealtimePhase.FINISHED.ordinal, RealtimePhase.ROUTE_UNAVAILABLE.ordinal -> pendingPosition = renderedPosition
            else -> send(2, renderedPosition.toDouble())
        }
    }
    @Synchronized override fun setSpeed(speed: Float) {
        selectedSpeed = speed
        if (handle != 0L) send(3, speed.toDouble())
    }
    @Synchronized override fun setMix(enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, overrides: List<Boolean>) {
        val routed = routing.mix(enabled, volumes, programs, overrides)
        val mix = Selection(routed.enabled, routed.volumes, routed.programs, routed.overrides)
        selectedMix = mix
        if (handle != 0L) configure(handle, mix.programs, mix.volumes, mix.enabled, mix.overrides)
    }
    override fun retryAudioRoute() = send(4)
    @Synchronized override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(bank: String, starts: IntArray, ends: IntArray, channels: IntArray,
                                pitches: IntArray, velocities: IntArray, notePrograms: IntArray, noteBanks: IntArray,
                                drums: IntArray, partPrograms: IntArray, partBanks: IntArray,
                                controlStarts: IntArray, controlEnds: IntArray, controlParts: IntArray,
                                controllers: IntArray, controlFrom: IntArray, controlTo: IntArray, duration: Int): Long
    private external fun control(handle: Long, operation: Int, first: Double, second: Double)
    private external fun configure(handle: Long, programs: IntArray, volumes: IntArray, enabled: BooleanArray, overrides: BooleanArray)
    private external fun state(handle: Long, property: Int): Double
    private external fun destroy(handle: Long)
}
