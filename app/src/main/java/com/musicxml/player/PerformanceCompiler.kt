package com.musicxml.player

import kotlin.math.roundToInt

object PerformanceCompiler {
    private const val MAX_MILLIS = 30 * 60 * 1000

    fun compile(score: Score, style: PerformanceStyle = PerformanceStyle.ORIGINAL): PerformancePlan {
        val canonicalEnd = score.millisAt(score.endTick)
        val ticks = (score.notes.flatMap { listOf(it.tick, it.tick + it.duration) } +
            score.controls.flatMap { listOf(it.tick, it.endTick) } + listOf(0, score.endTick))
            .filter { it in 0..score.endTick }.distinct().sorted()
        val offsets = ticks.map { timingOffset(score, it, style) }
        val mapped = IntArray(ticks.size)
        var previous = 0
        ticks.indices.forEach { index ->
            mapped[index] = if (index == 0) 0 else
                maxOf(previous, score.millisAt(ticks[index]) + offsets[index])
            previous = mapped[index]
        }

        fun at(tick: Int): Int {
            val index = ticks.binarySearch(tick)
            return (if (index >= 0) mapped[index]
                else score.millisAt(tick) + timingOffset(score, tick, style)).coerceAtLeast(0)
        }
        val notes = score.notes.map { note ->
            val metronome = score.parts.getOrNull(note.part)?.id == APP_METRONOME_ID
            val start = (if (metronome) score.millisAt(note.tick) else at(note.tick)).coerceAtLeast(0)
            val baseEnd = if (metronome) score.millisAt(note.tick + note.duration)
            else at((note.tick + note.duration).coerceAtMost(score.endTick))
            val durationScale = if (metronome) 1.0 else durationScale(note, style)
            val endMillis = maxOf(start + 1, start + ((baseEnd - start) * durationScale).roundToInt())
            PerformanceNote(note.copy(velocity = if (metronome) note.velocity else velocity(note, style)), start,
                endMillis)
        }
        val controls = score.controls.map { control ->
            val start = at(control.tick)
            PerformanceControl(control, start, maxOf(start, at(control.endTick)))
        }
        val duration = maxOf(canonicalEnd, mapped.lastOrNull() ?: 0, notes.maxOfOrNull { it.endMillis } ?: 0,
            controls.maxOfOrNull { it.endMillis } ?: 0)
        require(duration <= MAX_MILLIS) { "目前支援最長 30 分鐘的樂譜" }
        val finalMapping = mapped.toMutableList().also { values ->
            if (values.isNotEmpty()) values[values.lastIndex] = duration
        }
        return PerformancePlan(score, style, notes, controls, duration,
            ticks.map(score::millisAt), finalMapping)
    }

    private fun timingOffset(score: Score, tick: Int, style: PerformanceStyle): Int {
        if (style == PerformanceStyle.ORIGINAL) return 0
        val beat = tick.toDouble() / PPQ
        val fraction = (tick % PPQ + PPQ) % PPQ
        return when (style) {
            PerformanceStyle.POP -> if (fraction >= PPQ / 2) 8 else 0
            PerformanceStyle.LYRICAL -> (4.0 * kotlin.math.sin(beat * Math.PI / 2)).roundToInt()
            PerformanceStyle.NATURAL -> ((tick / PPQ * 17 + tick % PPQ / 30) % 9) - 4
            PerformanceStyle.ROMANTIC -> if (fraction == 0) -5 else 7
            PerformanceStyle.ORIGINAL -> 0
        }
    }

    private fun durationScale(note: Note, style: PerformanceStyle): Double = when (style) {
        PerformanceStyle.ORIGINAL -> 1.0
        PerformanceStyle.POP -> if (note.tick % PPQ >= PPQ / 2) 0.94 else 1.0
        PerformanceStyle.LYRICAL -> 1.08
        PerformanceStyle.NATURAL -> 0.98 + ((note.pitch + note.part) % 5) * 0.01
        PerformanceStyle.ROMANTIC -> 1.12
    }

    private fun velocity(note: Note, style: PerformanceStyle): Int {
        val change = when (style) {
            PerformanceStyle.ORIGINAL -> 0
            PerformanceStyle.POP -> if (note.tick % PPQ == 0) 5 else 0
            PerformanceStyle.LYRICAL -> -3
            PerformanceStyle.NATURAL -> ((note.pitch * 13 + note.part * 7) % 7) - 3
            PerformanceStyle.ROMANTIC -> if (note.pitch < 64) 3 else -2
        }
        return (note.velocity + change).coerceIn(1, 127)
    }
}
