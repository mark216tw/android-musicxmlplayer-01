package com.musicxml.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceStyleTest {
    private fun score() = Score(
        "test", "", listOf(Part("p", "Piano", 0, false)),
        listOf(Note(0, PPQ, 60, 80, 0), Note(0, PPQ, 64, 80, 0), Note(PPQ, PPQ, 67, 80, 0)),
        listOf(Tempo(0, 120.0)), listOf(Bar("1", 0, PPQ * 2)), PPQ * 2, emptyList()
    )

    @Test fun compilationIsDeterministicAndNonDestructive() {
        val source = score()
        val before = source.notes
        val first = PerformanceCompiler.compile(source, PerformanceStyle.NATURAL)
        val second = PerformanceCompiler.compile(source, PerformanceStyle.NATURAL)
        assertEquals(first, second)
        assertEquals(before, source.notes)
    }

    @Test fun chordMembersShareTimingOffset() {
        val plan = PerformanceCompiler.compile(score(), PerformanceStyle.ROMANTIC)
        assertEquals(plan.notes[0].startMillis, plan.notes[1].startMillis)
    }

    @Test fun originalMatchesScoreConversion() {
        val source = score()
        val plan = PerformanceCompiler.compile(source)
        assertEquals(source.millisAt(source.endTick), plan.durationMillis)
        source.notes.forEachIndexed { index, note ->
            assertEquals(source.millisAt(note.tick), plan.notes[index].startMillis)
            assertEquals(maxOf(plan.notes[index].startMillis + 1, source.millisAt(note.tick + note.duration)),
                plan.notes[index].endMillis)
        }
        for (millis in 0..plan.durationMillis step 7) {
            assertEquals(millis, plan.performanceMillisAt(millis))
            assertEquals(millis, plan.canonicalMillisAt(millis))
        }
    }

    @Test fun mapIsMonotonicAndStylesDiffer() {
        val original = PerformanceCompiler.compile(score(), PerformanceStyle.ORIGINAL)
        val styled = PerformanceCompiler.compile(score(), PerformanceStyle.POP)
        assertNotEquals(original.notes, styled.notes)
        var previous = 0
        for (millis in 0..styled.durationMillis step 7) {
            val mapped = styled.performanceMillisAt(millis)
            assertTrue(mapped >= previous)
            previous = mapped
        }
        assertTrue(styled.notes.all { it.startMillis >= 0 && it.endMillis <= styled.durationMillis })
    }

    @Test fun styledFinalNoteTailIsFullyPreservedAndSetsRenderedDuration() {
        val source = score()
        val plan = PerformanceCompiler.compile(source, PerformanceStyle.ROMANTIC)
        val finalNote = plan.notes.last()
        assertTrue(finalNote.endMillis > source.millisAt(source.endTick))
        assertEquals(finalNote.endMillis, plan.durationMillis)
        assertEquals(plan.durationMillis, plan.performanceMillisAt(source.millisAt(source.endTick)))
        assertEquals(source.millisAt(source.endTick), plan.canonicalMillisAt(plan.durationMillis))
    }

    @Test fun canonicalTrailingSilenceIsRetained() {
        val source = score().copy(notes = listOf(Note(0, PPQ, 60, 80, 0)))
        val plan = PerformanceCompiler.compile(source, PerformanceStyle.LYRICAL)
        assertTrue(plan.notes.single().endMillis < source.millisAt(source.endTick))
        assertEquals(source.millisAt(source.endTick), plan.durationMillis)
        assertEquals(plan.durationMillis, plan.performanceMillisAt(source.millisAt(source.endTick)))
    }

    @Test fun renderedEndAnchorAfterTrailingSilenceIsRetained() {
        val source = score().copy(notes = listOf(Note(0, PPQ / 4, 60, 80, 0)), endTick = PPQ * 3 / 2,
            bars = listOf(Bar("1", 0, PPQ * 3 / 2)))
        val plan = PerformanceCompiler.compile(source, PerformanceStyle.POP)
        assertEquals(source.millisAt(source.endTick) + 8, plan.durationMillis)
        assertEquals(plan.durationMillis, plan.performanceMillisAt(source.millisAt(source.endTick)))
        assertEquals(source.millisAt(source.endTick), plan.canonicalMillisAt(plan.durationMillis))
    }

    @Test fun renderedDurationIncludesControlEnds() {
        val source = score().copy(notes = emptyList(), endTick = PPQ / 2,
            bars = listOf(Bar("1", 0, PPQ / 2)), controls = listOf(Control(0, PPQ / 2, 0, 11, 0, 127)))
        val plan = PerformanceCompiler.compile(source, PerformanceStyle.POP)
        assertEquals(258, plan.controls.single().endMillis)
        assertEquals(258, plan.durationMillis)
    }

    @Test fun exactThirtyMinuteRenderedDurationIsAcceptedButStyledOverflowIsRejected() {
        val ticks = 1_728_000
        val source = Score("limit", "", listOf(Part("p", "Piano", 0, false)),
            listOf(Note(0, ticks, 60, 80, 0)), listOf(Tempo(0, 120.0)),
            listOf(Bar("1", 0, ticks)), ticks, emptyList())
        assertEquals(1_800_000, PerformanceCompiler.compile(source).durationMillis)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PerformanceCompiler.compile(source, PerformanceStyle.LYRICAL)
        }
        assertTrue(failure.message.orEmpty().contains("30 分鐘"))
    }

    @Test fun startsAreNonnegativeAndEveryNoteLastsAtLeastOneMillisecond() {
        val source = score().copy(notes = listOf(Note(0, 0, 60, 80, 0)))
        PerformanceStyle.entries.forEach { style ->
            val note = PerformanceCompiler.compile(source, style).notes.single()
            assertTrue(note.startMillis >= 0)
            assertTrue(note.endMillis - note.startMillis >= 1)
        }
    }

    @Test fun mappingUsesLatestDuplicateCanonicalAnchorAndRenderedPlateau() {
        val duplicates = plan(listOf(0, 100, 100, 200), listOf(0, 80, 120, 200), 200)
        assertEquals(120, duplicates.performanceMillisAt(100))
        assertEquals(160, duplicates.performanceMillisAt(150))

        val plateau = plan(listOf(0, 80, 120, 200), listOf(0, 100, 100, 200), 200)
        assertEquals(120, plateau.canonicalMillisAt(100))
        assertEquals(160, plateau.canonicalMillisAt(150))
    }

    @Test fun mappingClampsEndpointsBoundsAndRenderedTail() {
        val plan = plan(listOf(0, 100, 200), listOf(0, 100, 220), 250)
        assertEquals(0, plan.performanceMillisAt(-50))
        assertEquals(250, plan.performanceMillisAt(200))
        assertEquals(250, plan.performanceMillisAt(500))
        assertEquals(0, plan.canonicalMillisAt(-50))
        assertEquals(200, plan.canonicalMillisAt(230))
        assertEquals(200, plan.canonicalMillisAt(250))
        assertEquals(200, plan.canonicalMillisAt(500))
    }

    @Test fun mappingIsMonotonicWithNegativeRenderedOffsetsAndUsesLongInterpolation() {
        val plan = plan(listOf(0, 900_000, 1_800_000), listOf(-20, 1_200_000, 1_800_000), 1_800_000)
        assertEquals(599_990, plan.performanceMillisAt(450_000))
        var rendered = 0
        for (canonical in 0..1_800_000 step 997) {
            val current = plan.performanceMillisAt(canonical)
            assertTrue(current in rendered..1_800_000)
            rendered = current
        }
        var canonical = 0
        for (performance in 0..1_800_000 step 991) {
            val current = plan.canonicalMillisAt(performance)
            assertTrue(current in canonical..1_800_000)
            canonical = current
        }
    }

    @Test fun zeroDurationMappingIsSafe() {
        val empty = Score("empty", "", listOf(Part("p", "Piano", 0, false)), emptyList(),
            listOf(Tempo(0, 120.0)), emptyList(), 0, emptyList())
        val plan = PerformancePlan(empty, PerformanceStyle.ORIGINAL, emptyList(), emptyList(), 0,
            listOf(0), listOf(0))
        assertEquals(0, plan.performanceMillisAt(Int.MIN_VALUE))
        assertEquals(0, plan.performanceMillisAt(Int.MAX_VALUE))
        assertEquals(0, plan.canonicalMillisAt(Int.MIN_VALUE))
        assertEquals(0, plan.canonicalMillisAt(Int.MAX_VALUE))
    }

    private fun plan(canonical: List<Int>, rendered: List<Int>, duration: Int): PerformancePlan {
        val endMillis = canonical.last()
        val endTick = endMillis * PPQ / 500
        val source = Score("mapping", "", listOf(Part("p", "Piano", 0, false)), emptyList(),
            listOf(Tempo(0, 120.0)), emptyList(), endTick, emptyList())
        assertEquals(endMillis, source.millisAt(source.endTick))
        return PerformancePlan(source, PerformanceStyle.ORIGINAL, emptyList(), emptyList(), duration,
            canonical, rendered)
    }
}
