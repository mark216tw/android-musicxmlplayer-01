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
    private const val BANK_BYTES = 32319396L

    @Synchronized fun ensure(context: Context): File {
        val bank = File(context.filesDir, "GeneralUser-GS.sf2")
        if (bank.length() != BANK_BYTES) {
            val temporary = File(context.filesDir, "GeneralUser-GS.tmp")
            temporary.delete()
            context.assets.open("GeneralUser-GS.sf2").use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.length() == BANK_BYTES) { "採樣音源複製不完整" }
            // Remove a stale or partial bank first so renameTo can replace it on every filesystem.
            if (bank.exists() && !bank.delete()) check(false) { "無法更新採樣音源" }
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
    private val statusValues = DoubleArray(14)
    private val score = plan.source
    private val routing = AudioRouting(score)
    init {
        val duration = plan.durationMillis
        require(duration <= 30 * 60 * 1000) { "目前支援最長 30 分鐘的樂譜" }
        require(score.parts.size <= 16) { "即時引擎最多支援 16 個聲部（包含節拍器）" }
        val count = plan.notes.size
        val starts = IntArray(count)
        val ends = IntArray(count)
        val channels = IntArray(count)
        val pitches = IntArray(count)
        val velocities = IntArray(count)
        val notePrograms = IntArray(count)
        val noteBanks = IntArray(count)
        val drums = IntArray(count)
        for (index in 0 until count) {
            val note = plan.notes[index]
            starts[index] = note.startMillis
            ends[index] = note.endMillis
            channels[index] = routing.noteChannel(note.source)
            pitches[index] = note.pitch
            velocities[index] = note.velocity
            notePrograms[index] = if (note.program >= 0) note.program else score.parts[note.part].program
            noteBanks[index] = if (note.bank >= 0) note.bank else score.parts[note.part].bank
            drums[index] = if (note.percussion) 1 else 0
        }
        val controlCount = plan.controls.size
        val controlStarts = IntArray(controlCount)
        val controlEnds = IntArray(controlCount)
        val controlParts = IntArray(controlCount)
        val controllers = IntArray(controlCount)
        val controlFrom = IntArray(controlCount)
        val controlTo = IntArray(controlCount)
        for (index in 0 until controlCount) {
            val control = plan.controls[index]
            controlStarts[index] = control.startMillis
            controlEnds[index] = control.endMillis
            controlParts[index] = control.source.part
            controllers[index] = control.source.controller
            controlFrom[index] = control.source.from
            controlTo[index] = control.source.to
        }
        handle = create(SoundBank.ensure(context).absolutePath, starts, ends, channels, pitches, velocities,
            notePrograms, noteBanks, drums, routing.basePrograms, routing.baseBanks, controlStarts, controlEnds,
            controlParts, controllers, controlFrom, controlTo, duration)
        check(handle != 0L) { "無法建立即時採樣引擎" }
    }
    /** Reads every native property in a single JNI call; callers must hold the monitor. */
    private fun snapshotValues(): DoubleArray? {
        if (handle == 0L) return null
        stateAll(handle, statusValues)
        return statusValues
    }
    override val currentPosition get() = synchronized(this) {
        val values = snapshotValues() ?: return@synchronized 0
        plan.canonicalMillisAt(pendingPosition ?: values[0].toInt())
    }
    override val isPlaying get() = synchronized(this) { snapshotValues()?.let { it[1] != 0.0 } ?: false }
    override val isFinished get() = synchronized(this) { snapshotValues()?.let { pendingPosition == null && it[2] != 0.0 } ?: false }
    override val errorMessage get() = synchronized(this) {
        val error = snapshotValues()?.get(3)?.toInt() ?: 0
        if (error == 0) null else "即時音訊裝置錯誤（$error）"
    }
    override val status get() = synchronized(this) {
        val values = snapshotValues() ?: return@synchronized RealtimeStatus(RealtimePhase.CLOSED, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val phases = RealtimePhase.entries
        val nativePhase = phases.getOrElse(values[4].toInt()) { RealtimePhase.ERROR }
        // The completion query waits for queued audio to be presented and publishes FINISHED.
        val phase = when {
            pendingPosition != null && nativePhase == RealtimePhase.FINISHED -> RealtimePhase.PAUSED
            pendingPosition == null && values[2] != 0.0 -> RealtimePhase.FINISHED
            else -> nativePhase
        }
        RealtimeStatus(phase, plan.canonicalMillisAt(pendingPosition ?: values[0].toInt()),
            values[3].toInt(), values[5].toInt(), values[6].toInt(), values[7].toInt(),
            values[8].toInt(), values[9].toLong(), values[10].toLong(), values[11].toInt(),
            values[12].toInt(), values[13].toInt())
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
        val nativePhase = snapshotValues()?.get(4)?.toInt() ?: return
        when (nativePhase) {
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
    private external fun stateAll(handle: Long, out: DoubleArray)
    private external fun destroy(handle: Long)
}
