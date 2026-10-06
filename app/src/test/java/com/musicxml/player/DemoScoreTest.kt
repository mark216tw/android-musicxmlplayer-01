package com.musicxml.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DemoScoreTest {
    @Test fun demoAssetExercisesACompleteChamberPopScore() {
        val bytes = RuntimeEnvironment.getApplication().assets.open("demo.musicxml").use { it.readBytes() }
        val score = MusicXml.parse(bytes)

        assertEquals("晨光小遊行", score.title)
        assertEquals("小小樂隊示範曲", score.composer)
        assertEquals(listOf("鋼琴", "長笛", "單簧管", "大提琴", "鼓組"), score.parts.map { it.name })
        assertEquals(listOf(0, 73, 71, 42, 0), score.parts.map { it.program })
        assertEquals(listOf(0, 1, 2, 3, 9), score.parts.map { it.channel })
        assertEquals(listOf(false, false, false, false, true), score.parts.map { it.percussion })
        assertEquals((0..4).toSet(), score.notes.map { it.part }.toSet())

        assertEquals(listOf("1", "2", "3", "4", "1", "2", "3", "4") +
            (5..12).map(Int::toString), score.bars.map { it.number })
        assertEquals(16, score.bars.size)
        assertTrue(score.millisAt(score.endTick) in 39_000..42_000)
        assertTrue(score.warnings.isEmpty())

        val contextCodes = score.contexts.map { it.code }.toSet()
        assertTrue(setOf("dynamic:p", "dynamic:mf", "dynamic:f", "crescendo", "diminuendo",
            "staccato", "accent", "breath-mark", "fermata").all { it in contextCodes })
        assertTrue(score.controls.any { it.part == 0 && it.controller == 64 && it.to > 0 })
        assertTrue(score.controls.any { it.controller == 11 && it.from != it.to })
        assertTrue(score.notes.any { it.part == 4 && it.percussion && it.channel == 9 })
        assertTrue(score.notes.filter { it.part == 4 }.map { it.pitch }.distinct().size >= 3)
        assertTrue(score.tempos.any { it.bpm == 100.0 })
        assertTrue(score.tempos.any { it.bpm == 84.0 })
        assertTrue(score.tempos.any { it.bpm in 84.1..99.9 })
        assertTrue(score.notes.any { it.part == 1 && it.duration < PPQ / 2 })
        assertTrue(score.bars.last().duration > PPQ * 4)
    }
}
