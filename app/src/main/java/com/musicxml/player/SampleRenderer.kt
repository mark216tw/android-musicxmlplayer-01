package com.musicxml.player

import android.content.Context
import java.io.File

object SampleRenderer {
    init { System.loadLibrary("sampler") }
    private external fun render(bank: String, output: String, starts: IntArray, ends: IntArray, parts: IntArray,
                                pitches: IntArray, velocities: IntArray, notePrograms: IntArray, noteBanks: IntArray,
                                programs: IntArray, partPrograms: IntArray, partBanks: IntArray,
                                overrides: BooleanArray, percussion: IntArray, volumes: IntArray, controlStarts: IntArray,
                                controlEnds: IntArray, controlParts: IntArray, controllers: IntArray,
                                controlFrom: IntArray, controlTo: IntArray, duration: Int)
    fun wave(context: Context, score: Score, enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, output: File,
             overrides: List<Boolean> = programs.mapIndexed { i, program -> program != score.parts[i].program }) =
        wave(context, PerformanceCompiler.compile(score), enabled, volumes, programs, output, overrides)

    fun wave(context: Context, plan: PerformancePlan, enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, output: File,
             overrides: List<Boolean> = programs.mapIndexed { i, program -> program != plan.source.parts[i].program }) {
        val score = plan.source
        val duration = plan.durationMillis
        require(duration <= 30 * 60 * 1000) { "第一版支援最長 30 分鐘的樂譜" }
        val bank = SoundBank.ensure(context)
        val routing = AudioRouting(score)
        val notes = plan.notes.filter { enabled.getOrElse(it.part) { true } }
        val count = notes.size
        val starts = IntArray(count)
        val ends = IntArray(count)
        val channels = IntArray(count)
        val pitches = IntArray(count)
        val velocities = IntArray(count)
        val notePrograms = IntArray(count)
        val noteBanks = IntArray(count)
        val drums = IntArray(count)
        for (index in 0 until count) {
            val note = notes[index]
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
        val mix = routing.mix(enabled, volumes, programs, overrides)
        render(bank.absolutePath, output.absolutePath, starts, ends, channels, pitches, velocities,
            notePrograms, noteBanks, mix.programs, routing.basePrograms, routing.baseBanks, mix.overrides,
            drums, mix.volumes, controlStarts, controlEnds, controlParts, controllers, controlFrom, controlTo, duration)
    }
}
