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
        val starts = notes.map { it.startMillis }.toIntArray()
        val ends = notes.map { it.endMillis }.toIntArray()
        val controlStarts = plan.controls.map { it.startMillis }.toIntArray()
        val controlEnds = plan.controls.map { it.endMillis }.toIntArray()
        val notePrograms = notes.map { if (it.program >= 0) it.program else score.parts[it.part].program }.toIntArray()
        val noteBanks = notes.map { if (it.bank >= 0) it.bank else score.parts[it.part].bank }.toIntArray()
        val mix = routing.mix(enabled, volumes, programs, overrides)
        render(bank.absolutePath, output.absolutePath, starts, ends,
            notes.map { routing.noteChannel(it.source) }.toIntArray(), notes.map { it.pitch }.toIntArray(), notes.map { it.velocity }.toIntArray(),
            notePrograms, noteBanks, mix.programs, routing.basePrograms, routing.baseBanks, mix.overrides,
            notes.map { if (it.percussion) 1 else 0 }.toIntArray(), mix.volumes, controlStarts, controlEnds,
            plan.controls.map { it.source.part }.toIntArray(), plan.controls.map { it.source.controller }.toIntArray(),
            plan.controls.map { it.source.from }.toIntArray(), plan.controls.map { it.source.to }.toIntArray(), duration)
    }
}
