package com.musicxml.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File

class ScoreTest {
    private fun score(measures: String, program: Int = 1, channel: Int = 1) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0"><work><work-title>Test</work-title></work>
        <part-list><score-part id="P"><part-name>Instrument</part-name>
        <midi-instrument id="I"><midi-program>$program</midi-program><midi-channel>$channel</midi-channel></midi-instrument>
        </score-part></part-list><part id="P">$measures</part></score-partwise>
    """.trimIndent().toByteArray()
    private fun note(step: String = "C", duration: Int = 1, extra: String = "") =
        "<note>$extra<pitch><step>$step</step><octave>4</octave></pitch><duration>$duration</duration></note>"
    private val attributes = "<attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>"

    @Test fun chordsBackupAndTransposeHaveCorrectTiming() {
        val bytes = score("<measure number='1'>$attributes<attributes><transpose><chromatic>-2</chromatic></transpose></attributes>" +
            note() + note("E", extra = "<chord/>") + "<backup><duration>1</duration></backup>" + note("G", 4) + "</measure>")
        val parsed = MusicXml.parse(bytes)
        assertEquals(listOf(58, 62, 65), parsed.notes.map { it.pitch })
        assertTrue(parsed.notes.all { it.tick == 0 })
        assertEquals(1920, parsed.endTick)
    }
    @Test fun tiesAcrossMeasuresAreMerged() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes${note(duration = 4, extra = "<tie type='start'/>")}</measure>" +
            "<measure number='2'>${note(duration = 4, extra = "<tie type='stop'/>")}</measure>"))
        assertEquals(1, parsed.notes.size)
        assertEquals(3840, parsed.notes.single().duration)
        assertTrue(parsed.notes.single().velocity in 1..127)
    }
    @Test fun fractionalDivisionsAccumulateWithoutDrift() {
        val parsed = MusicXml.parse(score("<measure number='1'><attributes><divisions>7</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>" +
            (1..7).joinToString("") { note(duration = 1) } + "</measure>"))
        assertEquals(listOf(0, 69, 137, 206, 274, 343, 411), parsed.notes.map { it.tick })
        assertEquals(480, parsed.endTick)
        assertEquals(480, parsed.notes.sumOf { it.duration })
    }
    @Test fun tiesWithDifferentStaffIdentityDoNotMerge() {
        fun tied(staff: Int, type: String, chord: Boolean = false) =
            "<note>${if (chord) "<chord/>" else ""}<pitch><step>C</step><octave>4</octave></pitch><duration>4</duration>" +
                "<tie type='$type'/><voice>1</voice><staff>$staff</staff></note>"
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes${tied(1, "start")}${tied(2, "start", true)}</measure>" +
            "<measure number='2'>${tied(1, "stop")}${tied(2, "stop", true)}</measure>"))
        assertEquals(2, parsed.notes.size)
        assertTrue(parsed.notes.all { it.duration == 3840 })
        assertEquals(setOf(1, 2), parsed.notes.map { it.staff }.toSet())
    }
    @Test fun repeatAndAlternateEndingsExpandInPerformanceOrder() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'>${note("D", 4)}</measure>" +
            "<measure number='3'><barline><ending number='1' type='start'/></barline>${note("E", 4)}<barline><ending number='1' type='stop'/><repeat direction='backward'/></barline></measure>" +
            "<measure number='4'><barline><ending number='2' type='start'/></barline>${note("F", 4)}<barline><ending number='2' type='stop'/></barline></measure>"))
        assertEquals(listOf("1", "2", "3", "1", "2", "4"), parsed.bars.map { it.number })
        assertEquals(listOf(60, 62, 64, 60, 62, 65), parsed.notes.map { it.pitch })
    }
    @Test fun tempoChangesMapBothDirections() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes<direction><sound tempo='120'/></direction>${note(duration = 4)}</measure>" +
            "<measure number='2'><direction><sound tempo='60'/></direction>${note(duration = 4)}</measure>"))
        assertEquals(2000, parsed.millisAt(1920))
        assertEquals(6000, parsed.millisAt(3840))
        assertEquals(2880, parsed.tickAt(4000))
    }
    @Test fun compressedMusicXmlHonorsContainerRootfile() {
        val xml = score("<measure number='1'>$attributes${note(duration = 4)}</measure>")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("META-INF/container.xml"))
            it.write("<container><rootfiles><rootfile full-path='scores/main.musicxml' media-type='application/vnd.recordare.musicxml+xml'/></rootfiles></container>".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("scores/main.musicxml")); it.write(xml); it.closeEntry()
        }
        assertArrayEquals(xml, MusicXml.unpack(output.toByteArray()))
        assertEquals("Test", MusicXml.parse(output.toByteArray()).title)
    }
    @Test(timeout = 5000) fun mxlRejectsExcessiveEntryCountBeforeRetainingFiles() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            repeat(1025) { index ->
                zip.putNextEntry(ZipEntry("empty/$index"))
                zip.closeEntry()
            }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { MusicXml.unpack(output.toByteArray()) }
        assertTrue(failure.message.orEmpty().contains("ZIP 項目超過"))
    }
    @Test(timeout = 5000) fun mxlRejectsUnreasonableEntryName() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("a".repeat(1025)))
            zip.closeEntry()
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { MusicXml.unpack(output.toByteArray()) }
        assertTrue(failure.message.orEmpty().contains("ZIP 檔名超過"))
    }
    @Test fun typicalExternalDoctypeIsAcceptedWithoutLoadingIt() {
        val xml = String(score("<measure number='1'>$attributes${note(duration = 4)}</measure>"))
            .replace("<score-partwise", "<!DOCTYPE score-partwise PUBLIC \"MusicXML\" \"https://invalid.example/musicxml.dtd\"><score-partwise")
        assertEquals(1, MusicXml.parse(xml.toByteArray()).notes.size)
    }
    @Test fun internalEntitiesAreRejected() {
        val xml = "<!DOCTYPE score-partwise [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><score-partwise/>"
        assertThrows(Exception::class.java) { MusicXml.parse(xml.toByteArray()) }
    }
    @Test fun utf16ExportsAreNormalizedForPlayback() {
        val xml = String(score("<measure number='1'>$attributes${note(duration = 4)}</measure>"))
            .replace("UTF-8", "UTF-16")
        val encoded = xml.toByteArray(Charsets.UTF_16)
        assertEquals("Test", MusicXml.parse(encoded).title)
        assertTrue(String(MusicXml.normalizedXml(encoded)).contains("UTF-8"))
    }
    @Test fun tempoIsRestoredWhenRepeatJumpsBack() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'><direction><sound tempo='60'/></direction>${note(duration = 4)}<barline><repeat direction='backward'/></barline></measure>"))
        assertEquals(6000, parsed.millisAt(3840))
        assertEquals(8000, parsed.millisAt(5760))
        assertEquals(12000, parsed.millisAt(7680))
    }
    @Test fun daCapoIgnoresFineFirstTimeAndStopsThereAfterJump() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes${note(duration = 4)}<direction><direction-type><words>Fine</words></direction-type><sound fine='yes'/></direction></measure>" +
            "<measure number='2'>${note("D", 4)}</measure>" +
            "<measure number='3'>${note("E", 4)}<direction><direction-type><words>D.C. al Fine</words></direction-type><sound dacapo='yes'/></direction></measure>"))
        assertEquals(listOf("1", "2", "3", "1"), parsed.bars.map { it.number })
        assertEquals(listOf(60, 62, 64, 60), parsed.notes.map { it.pitch })
        assertEquals(listOf(0, 1, 2, 0), parsed.bars.map { it.sourceMeasure })
    }
    @Test fun nestedRepeatsUseIndependentPasses() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'><barline><repeat direction='forward'/></barline>${note("D", 4)}</measure>" +
            "<measure number='3'>${note("E", 4)}<barline><repeat direction='backward'/></barline></measure>" +
            "<measure number='4'>${note("F", 4)}<barline><repeat direction='backward'/></barline></measure>"))
        assertEquals(listOf("1", "2", "3", "2", "3", "4", "1", "2", "3", "2", "3", "4"), parsed.bars.map { it.number })
    }
    @Test fun nestedRepeatWithEndingsRestartsOnOuterPass() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'><barline><repeat direction='forward'/></barline>${note("D", 4)}</measure>" +
            "<measure number='3'><barline><ending number='1' type='start'/></barline>${note("E", 4)}<barline><ending number='1' type='stop'/><repeat direction='backward'/></barline></measure>" +
            "<measure number='4'><barline><ending number='2' type='start'/></barline>${note("F", 4)}<barline><ending number='2' type='stop'/></barline></measure>" +
            "<measure number='5'>${note("G", 4)}<barline><repeat direction='backward'/></barline></measure>"))
        assertEquals(listOf("1", "2", "3", "2", "4", "5", "1", "2", "3", "2", "4", "5"), parsed.bars.map { it.number })
    }
    @Test fun dalSegnoAlCodaUsesNamedMarkersOnce() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes${note(duration = 4)}</measure>" +
            "<measure number='2'><direction><sound segno='S'/></direction>${note("D", 4)}</measure>" +
            "<measure number='3'>${note("E", 4)}<direction><sound tocoda='C'/></direction></measure>" +
            "<measure number='4'>${note("F", 4)}</measure>" +
            "<measure number='5'><direction><sound coda='C'/></direction>${note("G", 4)}</measure>" +
            "<measure number='6'>${note("A", 4)}<direction><sound dalsegno='S'/></direction></measure>"))
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "2", "3", "5", "6"), parsed.bars.map { it.number })
    }
    @Test fun dalSegnoStartsAtMarkerInsideMeasure() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes${note(duration = 1)}<direction><sound segno='S'/></direction>${note("D", 3)}</measure>" +
            "<measure number='2'>${note("E", 4)}</measure>" +
            "<measure number='3'>${note("F", 4)}<direction><sound dalsegno='S'/></direction></measure>"))
        assertEquals(listOf(60, 62, 64, 65, 62, 64, 65), parsed.notes.map { it.pitch })
        assertEquals(0, parsed.bars[3].sourceMeasure)
        assertEquals(1440, parsed.bars[3].duration)
    }
    @Test fun endingRangesAreParsed() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'><barline><ending number='1-2' type='start'/></barline>${note("D", 4)}<barline><ending number='1-2' type='stop'/><repeat direction='backward' times='3'/></barline></measure>" +
            "<measure number='3'><barline><ending number='3' type='start'/></barline>${note("E", 4)}<barline><ending number='3' type='discontinue'/></barline></measure>"))
        assertEquals(listOf("1", "2", "1", "2", "1", "3"), parsed.bars.map { it.number })
    }
    @Test(timeout = 5000) fun hugeEndingRangeIsRejectedBeforeExpansion() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MusicXml.parse(score("<measure number='1'>$attributes" +
                "<barline><ending number='1-2147483647' type='start'/></barline>${note(duration = 4)}</measure>"))
        }
        assertTrue(failure.message.orEmpty().contains("結尾範圍"))
    }
    @Test(timeout = 5000) fun hugeTrillAndTremoloDurationsAreRejectedQuickly() {
        val trill = "<notations><ornaments><trill-mark/></ornaments></notations>"
        val tremolo = "<notations><ornaments><tremolo type='single'>8</tremolo></ornaments></notations>"
        listOf(
            note(duration = 10_000_001, extra = trill),
            note(duration = 600_001, extra = tremolo)
        ).forEach { unsafeNote ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                MusicXml.parse(score("<measure number='1' implicit='yes'><attributes><divisions>480</divisions></attributes>" +
                    "$unsafeNote</measure>"))
            }
            assertTrue(failure.message.orEmpty().contains("tick 超出安全範圍") ||
                failure.message.orEmpty().contains("解析事件過多"))
        }
    }
    @Test(timeout = 5000) fun aggregateMeasuredTremoloEventsAreRejectedBeforeExpansion() {
        fun tremolo(type: String) = "<note><pitch><step>C</step><octave>4</octave></pitch><duration>250001</duration>" +
            "<notations><ornaments><tremolo type='$type'>8</tremolo></ornaments></notations></note>"
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MusicXml.parse(score("<measure number='1' implicit='yes'><attributes><divisions>480</divisions></attributes>" +
                tremolo("start") + tremolo("stop") + "</measure>"))
        }
        assertTrue(failure.message.orEmpty().contains("雙音震音") && failure.message.orEmpty().contains("解析事件過多"))
    }
    @Test(timeout = 5000) fun aggregateLongTempoRampsAreRejectedBeforeGeneration() {
        val hints = (1..7).joinToString("") {
            "<direction><direction-type><words>rit.</words></direction-type></direction>"
        }
        val anchor = "<direction><offset>10000000</offset><sound tempo='60'/></direction>"
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MusicXml.parse(score("<measure number='1' implicit='yes'><attributes><divisions>480</divisions></attributes>" +
                hints + anchor + note(duration = 10_000_000) + "</measure>"))
        }
        assertTrue(failure.message.orEmpty().contains("tempo ramp") && failure.message.orEmpty().contains("解析事件過多"))
    }
    @Test(timeout = 5000) fun cumulativeTickOverflowIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MusicXml.parse(score("<measure number='1' implicit='yes'><attributes><divisions>480</divisions></attributes>" +
                "<forward><duration>9000000</duration></forward><forward><duration>9000000</duration></forward></measure>"))
        }
        assertTrue(failure.message.orEmpty().contains("tick 超出安全範圍"))
    }
    @Test(timeout = 5000) fun hugeControlTickIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MusicXml.parse(score("<measure number='1'>$attributes<direction><offset>2147483647</offset>" +
                "<direction-type><pedal type='start'/></direction-type></direction>${note(duration = 4)}</measure>"))
        }
        assertTrue(failure.message.orEmpty().contains("控制事件的 tick 超出安全範圍"))
    }
    @Test fun normalScoreStillParsesWithSafetyLimits() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes${note(duration = 4)}</measure>" +
            "<measure number='2'>${note("D", 4)}</measure>"))
        assertEquals(2, parsed.notes.size)
        assertEquals(3840, parsed.endTick)
        assertEquals(4000, parsed.millisAt(parsed.endTick))
    }
    @Test fun expandedPlaybackHonorsThirtyMinuteBoundary() {
        fun longNote(duration: Int) = score("<measure number='1' implicit='yes'>" +
            "<attributes><divisions>480</divisions></attributes>${note(duration = duration)}</measure>")
        val boundary = MusicXml.parse(longNote(1_728_000))
        assertEquals(1_800_000, boundary.millisAt(boundary.endTick))
        val decimalTempoBoundary = MusicXml.parse(score("<measure number='1' implicit='yes'>" +
            "<attributes><divisions>480</divisions></attributes><direction><sound tempo='0.03333333333333333'/></direction>" +
            "${note(duration = 480)}</measure>"))
        assertEquals(1_800_000, decimalTempoBoundary.millisAt(decimalTempoBoundary.endTick))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MusicXml.parse(longNote(1_728_001))
        }
        assertTrue(failure.message.orEmpty().contains("超過 30 分鐘"))
    }
    @Test fun daCapoSelectsFinalEndingWithinEachEndingGroup() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'><barline><ending number='1' type='start'/></barline>${note("D", 4)}<barline><ending number='1' type='stop'/><repeat direction='backward'/></barline></measure>" +
            "<measure number='3'><barline><ending number='2' type='start'/></barline>${note("E", 4)}<barline><ending number='2' type='stop'/></barline></measure>" +
            "<measure number='4'>${note("F", 4)}</measure>" +
            "<measure number='5'><barline><repeat direction='forward'/></barline>${note("G", 4)}</measure>" +
            "<measure number='6'><barline><ending number='1' type='start'/></barline>${note("A", 4)}<barline><ending number='1' type='stop'/><repeat direction='backward'/></barline></measure>" +
            "<measure number='7'><barline><ending number='2' type='start'/></barline>${note("B", 4)}<barline><ending number='2' type='stop'/></barline></measure>" +
            "<measure number='8'>${note("C", 4)}<direction><sound dacapo='yes'/></direction></measure>"))
        assertEquals(listOf("1", "2", "1", "3", "4", "5", "6", "5", "7", "8",
            "1", "3", "4", "5", "7", "8"), parsed.bars.map { it.number })
    }
    @Test fun graceNotesStealTimeFromFollowingNote() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><grace slash='yes'/><pitch><step>D</step><octave>4</octave></pitch></note>" + note(duration = 4) + "</measure>"))
        assertEquals(listOf(62, 60), parsed.notes.map { it.pitch })
        assertEquals(0, parsed.notes[0].tick)
        assertEquals(parsed.notes[0].duration, parsed.notes[1].tick)
        assertEquals(1920, parsed.notes.sumOf { it.duration })
    }
    @Test fun graceChordNotesShareTheirOnset() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><grace slash='yes'/><pitch><step>D</step><octave>4</octave></pitch></note>" +
            "<note><grace slash='yes'/><chord/><pitch><step>F</step><octave>4</octave></pitch></note>" + note(duration = 4) + "</measure>"))
        assertEquals(parsed.notes[0].tick, parsed.notes[1].tick)
        assertEquals(parsed.notes[0].duration, parsed.notes[1].duration)
        assertEquals(listOf(62, 65), parsed.notes.take(2).map { it.pitch })
    }
    @Test fun pendingGraceOnlyAttachesToMatchingVoiceAndStaffAcrossBackupAndMeasures() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><grace slash='yes'/><pitch><step>D</step><octave>4</octave></pitch><voice>1</voice><staff>1</staff></note>" +
            "<backup><duration>1</duration></backup>" +
            "<note><grace slash='yes'/><chord/><pitch><step>F</step><octave>4</octave></pitch><voice>2</voice><staff>2</staff></note>" +
            "<note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice><staff>2</staff></note></measure>" +
            "<measure number='2'><note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><staff>1</staff></note></measure>"))
        val d = parsed.notes.single { it.pitch == 62 }
        val f = parsed.notes.single { it.pitch == 65 }
        assertEquals(1920, d.tick)
        assertEquals("1" to 1, d.voice to d.staff)
        assertEquals(0, f.tick)
        assertEquals("2" to 2, f.voice to f.staff)
        assertEquals(240, parsed.notes.single { it.pitch == 67 }.tick)
        assertEquals(2160, parsed.notes.single { it.pitch == 64 }.tick)
    }
    @Test fun trillAndArticulationsCreatePerformanceEvents() {
        val trill = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><trill-mark/></ornaments></notations></note></measure>"))
        assertTrue(trill.notes.size > 2)
        assertEquals(listOf(60, 62, 60, 62), trill.notes.take(4).map { it.pitch })
        val articulated = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><articulations><staccato/><accent/></articulations></notations></note></measure>"))
        assertEquals(960, articulated.notes.single().duration)
        assertEquals(100, articulated.notes.single().velocity)
    }
    @Test fun trillHonorsOrnamentAccidental() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><trill-mark/><accidental-mark>flat</accidental-mark></ornaments></notations></note></measure>"))
        assertEquals(61, parsed.notes[1].pitch)
    }
    @Test fun graceSupportsPreviousAndMakeTimeWithoutBreakingGraceChords() {
        val previous = MusicXml.parse(score("<measure number='1'>$attributes${note(duration = 2)}" +
            "<note><grace steal-time-previous='25'/><pitch><step>D</step><octave>4</octave></pitch></note>" +
            "<note><grace steal-time-previous='25'/><chord/><pitch><step>F</step><octave>4</octave></pitch></note>" +
            note("E", 2) + "</measure>"))
        assertEquals(720, previous.notes.first { it.pitch == 60 }.duration)
        assertEquals(listOf(720, 720), previous.notes.filter { it.pitch in setOf(62, 65) }.map { it.tick })
        assertEquals(listOf(240, 240), previous.notes.filter { it.pitch in setOf(62, 65) }.map { it.duration })
        assertEquals(960, previous.notes.first { it.pitch == 64 }.tick)

        val made = MusicXml.parse(score("<measure number='1'><attributes><divisions>4</divisions>" +
            "<time><beats>4</beats><beat-type>4</beat-type></time></attributes>" +
            "<note><grace make-time='2'/><pitch><step>D</step><octave>4</octave></pitch></note>" +
            note(duration = 4) + note("E", 4) + note("F", 4) + note("G", 4) + "</measure>"))
        assertEquals(listOf(0, 240, 720, 1200, 1680), made.notes.map { it.tick })
        assertEquals(listOf(240, 480, 480, 480, 480), made.notes.map { it.duration })
        assertEquals(2160, made.endTick)
    }
    @Test fun graceMakeTimeAccumulatesExactlyAndPreviousUsesNotatedStaccatoTime() {
        val sequence = (0 until 7).joinToString("") {
            "<note><grace make-time='1'/><pitch><step>D</step><octave>4</octave></pitch></note>" + note(duration = 1)
        }
        val made = MusicXml.parse(score("<measure number='1' implicit='yes'><attributes><divisions>7</divisions>" +
            "<time><beats>2</beats><beat-type>4</beat-type></time></attributes>$sequence</measure>"))
        assertEquals(960, made.endTick)
        assertEquals(960, made.notes.maxOf { it.tick + it.duration })

        val previous = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration>" +
            "<notations><articulations><staccato/></articulations></notations></note>" +
            "<note><grace steal-time-previous='25'/><pitch><step>D</step><octave>4</octave></pitch></note>" +
            note("E", 2) + "</measure>"))
        assertEquals(480, previous.notes.first { it.pitch == 60 }.duration)
        assertEquals(720, previous.notes.first { it.pitch == 62 }.tick)
        assertEquals(960, previous.notes.first { it.pitch == 64 }.tick)
    }
    @Test fun mordentsAndTurnsHonorKeyAndAccidentalMarks() {
        fun ornament(name: String, accidental: String = "") = MusicXml.parse(score("<measure number='1'>" +
            "<attributes><divisions>1</divisions><key><fifths>1</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time></attributes>" +
            "<note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><$name/>$accidental</ornaments></notations></note></measure>"))
        assertEquals(listOf(64, 62, 64), ornament("mordent").notes.take(3).map { it.pitch })
        assertEquals(listOf(64, 66, 64), ornament("inverted-mordent").notes.take(3).map { it.pitch })
        assertEquals(listOf(66, 64, 62, 64), ornament("turn").notes.take(4).map { it.pitch })
        assertEquals(listOf(61, 64, 66, 64), ornament("inverted-turn", "<accidental-mark placement='below'>flat</accidental-mark>").notes.take(4).map { it.pitch })
        assertEquals(listOf(64, 64, 66, 64, 62, 64), ornament("delayed-turn").notes.take(6).map { it.pitch })
        assertEquals(listOf(64, 64, 62, 64, 66, 64), ornament("delayed-inverted-turn").notes.take(6).map { it.pitch })
        val sustained = ornament("turn")
        assertEquals(4, sustained.notes.size)
        assertEquals(1560, sustained.notes.last().duration)
    }
    @Test fun tremolosExpandAndTiedTremoloKeepsTieIdentity() {
        val single = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><notations><ornaments><tremolo type='single'>2</tremolo></ornaments></notations></note></measure>"))
        assertEquals(8, single.notes.size)
        assertTrue(single.notes.all { it.pitch == 60 && it.duration == 120 })

        val measured = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations><ornaments><tremolo type='start'>1</tremolo></ornaments></notations></note>" +
            "<note><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration><notations><ornaments><tremolo type='stop'>1</tremolo></ornaments></notations></note></measure>"))
        assertEquals(listOf(60, 67, 60, 67), measured.notes.map { it.pitch })
        assertEquals(listOf(0, 240, 480, 720), measured.notes.map { it.tick })

        val tied = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><tie type='start'/><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><tremolo type='single'>2</tremolo></ornaments></notations></note></measure>" +
            "<measure number='2'>${note(duration = 4, extra = "<tie type='stop'/>")}</measure>"))
        assertEquals(1, tied.notes.size)
        assertEquals(3840, tied.notes.single().duration)

        val crossing = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><dynamics><f/></dynamics></direction-type></direction>" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><tremolo type='start'>1</tremolo></ornaments></notations></note></measure>" +
            "<measure number='2'><note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><tremolo type='stop'>1</tremolo></ornaments></notations></note></measure>"))
        assertEquals(16, crossing.notes.size)
        assertEquals(List(16) { if (it % 2 == 0) 60 else 67 }, crossing.notes.map { it.pitch })
        assertTrue(crossing.notes.all { it.velocity == 100 })
        assertFalse(crossing.warnings.any { it.contains("雙音震音") })
    }
    @Test fun graceCanShortenMeasuredTremoloOwnerWithoutLeavingOriginalNote() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><notations><ornaments><tremolo type='start'>1</tremolo></ornaments></notations></note>" +
            "<note><grace steal-time-previous='25'/><pitch><step>D</step><octave>4</octave></pitch></note>" +
            "<note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration><notations><ornaments><tremolo type='stop'>1</tremolo></ornaments></notations></note></measure>"))
        assertFalse(parsed.notes.any { it.pitch == 60 && it.duration > PPQ / 2 })
        assertEquals(7, parsed.notes.count { it.pitch in setOf(60, 67) })
    }
    @Test fun fermataAndBreathingMarksChangeSoundingDuration() {
        fun articulation(mark: String) = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><notations>$mark</notations></note>" +
            note("D", 2) + "</measure>"))
        assertEquals(1440, articulation("<fermata/>").notes.first().duration)
        assertEquals(840, articulation("<articulations><breath-mark/></articulations>").notes.first().duration)
        assertEquals(720, articulation("<articulations><caesura/></articulations>").notes.first().duration)
        val finalFermata = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><fermata/></notations></note></measure>"))
        assertEquals(2880, finalFermata.notes.single().duration)
        assertEquals(2880, finalFermata.endTick)
    }
    @Test fun fermataOverridesStaccatoAndBreathShortensTremoloPattern() {
        val fermata = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations><fermata/><articulations><staccato/></articulations></notations></note>" +
            note("D", 3) + "</measure>"))
        assertEquals(720, fermata.notes.first().duration)
        assertEquals(720, fermata.notes.last().tick)

        val breathing = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><notations>" +
            "<ornaments><tremolo type='single'>2</tremolo></ornaments><articulations><breath-mark/></articulations>" +
            "</notations></note>${note("D", 2)}</measure>"))
        val tremolo = breathing.notes.filter { it.pitch == 60 }
        assertEquals(840, tremolo.maxOf { it.tick + it.duration })
        assertEquals(960, breathing.notes.first { it.pitch == 62 }.tick)
    }
    @Test fun fermataInsertionSynchronizesPartsEventsAndTieEndings() {
        val xml = """
            <score-partwise version="4.0"><part-list>
            <score-part id="P1"><part-name>One</part-name></score-part>
            <score-part id="P2"><part-name>Two</part-name></score-part></part-list>
            <part id="P1"><measure number="1">$attributes
            <direction><direction-type><wedge type="crescendo"/></direction-type></direction>
            <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations><fermata/></notations></note>
            <direction><direction-type><wedge type="stop"/><pedal type="start"/></direction-type><sound tempo="60"/></direction>
            ${note("D")}${note("E")}${note("F")}</measure></part>
            <part id="P2"><measure number="1">$attributes${note("G")}${note("A")}${note("B")}${note("C")}</measure></part>
            </score-partwise>
        """.trimIndent().toByteArray()
        val synchronized = MusicXml.parse(xml)
        assertEquals(listOf(0, 720, 1200, 1680), synchronized.notes.filter { it.part == 1 }.map { it.tick })
        assertEquals(720, synchronized.notes.first { it.part == 1 }.duration)
        assertTrue(synchronized.tempos.any { it.tick == 720 && it.bpm == 60.0 })
        assertTrue(synchronized.controls.any { it.part == 0 && it.controller == 64 && it.tick == 720 })
        assertTrue(synchronized.controls.any { it.part == 0 && it.controller == 11 && it.endTick == 720 })

        fun tiedEnding(mark: String) = MusicXml.parse(score(
            "<measure number='1'>$attributes${note(duration = 4, extra = "<tie type='start'/>")}</measure>" +
            "<measure number='2'><note><tie type='stop'/><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations>$mark</notations></note></measure>" +
            "<measure number='3'>${note("D", 4)}</measure>"))
        val fermata = tiedEnding("<fermata/>")
        assertEquals(4800, fermata.notes.first().duration)
        assertEquals(4800, fermata.notes.last().tick)
        val breath = tiedEnding("<articulations><breath-mark/></articulations>")
        assertEquals(3720, breath.notes.first().duration)
        assertEquals(3840, breath.notes.last().tick)
    }
    @Test fun tempoAtMeasureRightBoundaryIsRetained() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes${note(duration = 4)}" +
            "<direction><sound tempo='60'/></direction></measure><measure number='2'>${note("D", 4)}</measure>"))
        assertTrue(parsed.tempos.any { it.tick == 1920 && it.bpm == 60.0 })
        assertEquals(6000, parsed.millisAt(3840))
    }
    @Test fun supportedOrnamentsDoNotProduceUnsupportedWarning() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><ornaments><inverted-mordent/><accidental-mark>sharp</accidental-mark></ornaments></notations></note></measure>"))
        assertFalse(parsed.warnings.any { it.contains("部分裝飾奏法") })
    }
    @Test fun dynamicsOffsetAndWedgeAffectNoteVelocities() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><wedge type='crescendo'/></direction-type></direction>" +
            "<direction><offset>4</offset><direction-type><wedge type='stop'/></direction-type></direction>" +
            "<direction><offset>1</offset><direction-type><dynamics><f/></dynamics></direction-type></direction>" +
            note(duration = 1) + note("D", 1) + note("E", 1) + note("F", 1) + "</measure>"))
        assertEquals(88, parsed.notes[0].velocity)
        assertTrue(parsed.notes[1].velocity > parsed.notes[0].velocity)
        assertTrue(parsed.notes.last().velocity >= parsed.notes[1].velocity)
    }
    @Test fun wedgePreservesAccentDelta() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><wedge type='crescendo'/></direction-type></direction>" +
            "<direction><offset>4</offset><direction-type><wedge type='stop'/></direction-type></direction>" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations><articulations><accent/></articulations></notations></note>" +
            note("D", 1) + note("E", 1) + note("F", 1) + "</measure>"))
        assertEquals(12, parsed.notes[0].velocity - 88)
    }
    @Test fun wedgeCrossesMeasuresAndExpandsWithRepeats() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>" +
                "<direction><direction-type><wedge type='crescendo' number='2'/></direction-type></direction>${note(duration = 4)}</measure>" +
            "<measure number='2'>${note("D", 2)}<direction><direction-type><wedge type='stop' number='2'/></direction-type></direction>" +
                "${note("E", 2)}<barline><repeat direction='backward'/></barline></measure>"))
        val ramps = parsed.controls.filter { it.controller == 11 && it.endTick > it.tick }
        assertEquals(4, ramps.size)
        assertEquals(listOf(0, 1920, 3840, 5760), ramps.map { it.tick })
        assertTrue(ramps.all { it.to >= it.from })
        assertEquals(parsed.notes[0].velocity, parsed.notes[3].velocity)
        assertTrue(parsed.notes.all { it.velocity == 88 })
        assertEquals(listOf(0, 3840), parsed.contexts.filter { it.code == "crescendo" }.map { it.tick })
    }
    @Test fun unscopedWedgeUsesExpressionControlWithoutAlsoRewritingVelocities() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><wedge type='crescendo'/></direction-type></direction>" +
            note(duration = 1) + note("D", 1) + note("E", 1) + note("F", 1) +
            "<direction><direction-type><wedge type='stop'/></direction-type></direction></measure>"))
        assertTrue(parsed.controls.any { it.controller == 11 && it.endTick > it.tick && it.to > it.from })
        assertEquals(listOf(88, 88, 88, 88), parsed.notes.map { it.velocity })
    }
    @Test fun pedalDirectionsBecomeOrderedSustainControls() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><pedal type='start'/></direction-type></direction>${note(duration = 1)}" +
            "<direction><direction-type><pedal type='change'/></direction-type></direction>${note("D", 1)}" +
            "<direction><sound damper-pedal='no'/></direction>${note("E", 2)}</measure>"))
        assertEquals(listOf(0, 127, 0, 127, 0, 0), parsed.controls.filter { it.controller == 64 }.map { it.to })
        assertEquals(listOf(0, 0, 480, 480, 960, 1920), parsed.controls.filter { it.controller == 64 }.map { it.tick })
    }
    @Test fun tiesRemainContinuousWhenAnUnusedEndingIsSkipped() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>${note(duration = 4)}</measure>" +
            "<measure number='2'>${note(duration = 4, extra = "<tie type='start'/>")}</measure>" +
            "<measure number='3'><barline><ending number='1' type='start'/></barline>${note(duration = 4, extra = "<tie type='stop'/>")}<barline><ending number='1' type='stop'/><repeat direction='backward'/></barline></measure>" +
            "<measure number='4'><barline><ending number='2' type='start'/></barline>${note(duration = 4, extra = "<tie type='stop'/>")}<barline><ending number='2' type='stop'/></barline></measure>"))
        assertEquals(2, parsed.notes.count { it.duration == 3840 })
    }
    @Test fun jsonWrappedMusicXmlIsExtractedAndCanBeReopened() {
        val xml = String(score("<measure number='0' implicit='yes'>$attributes${note()}</measure>" +
            "<measure number='1'>${note("E", 4)}</measure>"))
            .replace("<score-partwise", "<!DOCTYPE score-partwise PUBLIC \"MusicXML\" \"https://invalid.example/musicxml.dtd\"><score-partwise")
        val bytes = JSONObject().put("title", "JSON title").put("meta", JSONObject().put("composer", "Mozart"))
            .put("musicxml", xml).toString().toByteArray()
        val parsed = MusicXml.parse(bytes)
        val stored = MusicXml.normalizedXml(bytes)
        assertTrue(String(stored).startsWith("<?xml"))
        assertFalse(String(stored).startsWith("{"))
        assertEquals(480, parsed.bars.first().duration)
        assertEquals(parsed, MusicXml.parse(stored))
    }
    @Test fun jsonWrapperValidationHasActionableMessages() {
        val invalid = listOf(
            "{}" to "缺少 musicxml",
            "{\"musicxml\":null}" to "必須是 XML 字串",
            "{\"musicxml\":123}" to "必須是 XML 字串",
            "{\"musicxml\":\"  \"}" to "不可為空",
            "{\"musicxml\":\"hello\"}" to "不是 XML",
            "{\"musicxml\":" to "JSON 樂譜格式錯誤",
            "[]" to "必須是包含 musicxml",
            "{} trailing" to "結尾含有多餘內容"
        )
        invalid.forEach { (json, message) ->
            val failure = assertThrows(Exception::class.java) { MusicXml.parse(json.toByteArray()) }
            assertTrue("Expected '$message' in '${failure.message}'", failure.message.orEmpty().contains(message))
        }
    }
    @Test fun wrappedInternalEntitiesStillFailValidation() {
        val xml = "<!DOCTYPE score-partwise [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><score-partwise/>"
        val json = JSONObject().put("musicxml", xml).toString()
        assertThrows(Exception::class.java) { MusicXml.parse(json.toByteArray()) }
    }
    @Test fun metronomeSupportsEveryBeatUnitDotsMetricModulationAndSoundPriority() {
        val units = linkedMapOf("maxima" to 32.0, "long" to 16.0, "breve" to 8.0, "whole" to 4.0,
            "half" to 2.0, "quarter" to 1.0, "eighth" to .5, "16th" to .25, "32nd" to .125,
            "64th" to .0625, "128th" to .03125, "256th" to .015625, "512th" to .0078125,
            "1024th" to .00390625)
        units.forEach { (unit, factor) ->
            val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
                "<direction><direction-type><metronome><beat-unit>$unit</beat-unit><per-minute>60</per-minute></metronome></direction-type></direction>" +
                "${note(duration = 4)}</measure>"))
            assertEquals(unit, 60.0 * factor, parsed.tempos.last().bpm, 0.000001)
        }
        val dotted = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><metronome><beat-unit>quarter</beat-unit><beat-unit-dot/><beat-unit-dot/><per-minute>60</per-minute></metronome></direction-type></direction>" +
            "${note(duration = 4)}</measure>"))
        assertEquals(105.0, dotted.tempos.last().bpm, 0.0)
        val modulation = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><sound tempo='120'/></direction>" +
            "<direction><direction-type><metronome><beat-unit>quarter</beat-unit><beat-unit>half</beat-unit></metronome></direction-type></direction>" +
            "${note(duration = 4)}</measure>"))
        assertEquals(240.0, modulation.tempos.last().bpm, 0.0)
        val priority = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><metronome><beat-unit>whole</beat-unit><per-minute>60</per-minute></metronome></direction-type><sound tempo='90'/></direction>" +
            "${note(duration = 4)}</measure>"))
        assertEquals(90.0, priority.tempos.last().bpm, 0.0)
    }
    @Test fun swingMovesNotesAndControlsButPreservesPairLengthAndRepeats() {
        val parsed = MusicXml.parse(score("<measure number='1'><attributes><divisions>2</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>" +
            "<barline><repeat direction='forward'/></barline>" +
            "<direction><direction-type><swing><first>2</first><second>1</second><swing-type>eighth</swing-type></swing></direction-type></direction>" +
            "<direction><direction-type><pedal type='start'/></direction-type><offset>1</offset></direction>" +
            note(duration = 1) + note("D", 1) + "<barline><repeat direction='backward'/></barline></measure>"))
        assertEquals(listOf(0, 320, 480, 800), parsed.notes.map { it.tick })
        assertEquals(listOf(320, 160, 320, 160), parsed.notes.map { it.duration })
        assertEquals(listOf(320, 800), parsed.controls.filter { it.controller == 64 && it.to == 127 }.map { it.tick })
        assertEquals(960, parsed.endTick)
    }
    @Test fun arpeggiateUsesNumberDirectionCommonEndAndNonArpeggiateCancels() {
        fun chord(mark: String) = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations>$mark</notations></note>" +
            "<note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><notations>$mark</notations></note>" +
            "<note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration><notations>$mark</notations></note></measure>"))
        val down = chord("<arpeggiate number='2' direction='down'/>")
        assertEquals(listOf(67 to 0, 64 to 30, 60 to 60), down.notes.map { it.pitch to it.tick })
        assertTrue(down.notes.all { it.tick + it.duration == 480 })
        val cancelled = chord("<arpeggiate number='2' direction='up'/><non-arpeggiate number='2'/>")
        assertTrue(cancelled.notes.all { it.tick == 0 && it.duration == 480 })

        val unequal = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><arpeggiate number='3'/></notations></note>" +
            "<note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><notations><arpeggiate number='3'/></notations></note>" +
            "<note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>3</duration><notations><arpeggiate number='3'/></notations></note></measure>"))
        assertEquals(listOf(1920, 930, 1380), unequal.notes.map { it.duration })
        assertEquals(listOf(1920, 960, 1440), unequal.notes.map { it.tick + it.duration })
    }
    @Test fun slurGraceAndWavyLineContinueAcrossMeasureBoundaries() {
        val slur = MusicXml.parse(score(
            "<measure number='1'>$attributes${note(duration = 4, extra = "<notations><slur type='start' number='4'/></notations>")}</measure>" +
            "<measure number='2'><note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice><staff>2</staff><notations><slur type='stop' number='4'/></notations></note></measure>"))
        assertEquals(1944, slur.notes.first().duration)
        assertEquals(1920, slur.notes.last().tick)

        val grace = MusicXml.parse(score("<measure number='1'>$attributes${note(duration = 4)}</measure>" +
            "<measure number='2'><note><grace steal-time-previous='25'/><pitch><step>D</step><octave>4</octave></pitch></note>${note("E", 4)}</measure>"))
        assertEquals(listOf(60 to (0 to 1440), 62 to (1440 to 480), 64 to (1920 to 1920)),
            grace.notes.map { it.pitch to (it.tick to it.duration) })

        fun wavy(type: String, step: String) = "<note><pitch><step>$step</step><octave>4</octave></pitch><duration>4</duration>" +
            "<notations><ornaments><wavy-line type='$type' number='3'/></ornaments></notations></note>"
        val trill = MusicXml.parse(score("<measure number='1'>$attributes${wavy("start", "C")}</measure>" +
            "<measure number='2'>${wavy("continue", "D")}</measure><measure number='3'>${wavy("stop", "E")}</measure>"))
        assertEquals(48, trill.notes.size)
        assertEquals(listOf(60, 62, 60, 62), trill.notes.take(4).map { it.pitch })
        assertEquals(listOf(64, 65, 64, 65), trill.notes.takeLast(4).map { it.pitch })
    }
    @Test fun steppedGlissandoCrossesMeasuresAndWarnsForUnsafeChordPairing() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><notations><glissando type='start' number='7'>chromatic</glissando></notations></note></measure>" +
            "<measure number='2'><note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration><notations><glissando type='stop' number='7'/></notations></note></measure>"))
        assertEquals((60..72).toList(), parsed.notes.map { it.pitch })
        assertEquals((0..11).map { it * 160 } + 1920, parsed.notes.map { it.tick })
        assertEquals(3840, parsed.endTick)

        val unsafe = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><notations><slide type='start' number='2'/></notations></note>" +
            "<note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration><notations><slide type='start' number='2'/></notations></note>" +
            "${note("G", 3, "<notations><slide type='stop' number='2'/></notations>")}</measure>"))
        assertTrue(unsafe.warnings.any { it.contains("無法安全配對") })
    }
    @Test fun instrumentMetadataAndAudioRoutingFollowScoreUnlessPartIsOverridden() {
        val xml = """
            <score-partwise version="4.0"><part-list><score-part id="P"><part-name>Multi</part-name>
            <score-instrument id="I1"><instrument-name>One</instrument-name></score-instrument>
            <score-instrument id="I2"><instrument-name>Two</instrument-name></score-instrument>
            <midi-instrument id="I1"><midi-program>1</midi-program><midi-bank>1</midi-bank><midi-channel>3</midi-channel></midi-instrument>
            <midi-instrument id="I2"><midi-program>41</midi-program><midi-bank>130</midi-bank><midi-channel>10</midi-channel><midi-unpitched>63</midi-unpitched></midi-instrument>
            </score-part></part-list><part id="P"><measure number="1">$attributes
            <direction><direction-type><pedal type="start"/></direction-type></direction>
            <note><instrument id="I1"/><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>
            <note><instrument id="I2"/><pitch><step>D</step><octave>4</octave></pitch><duration>3</duration></note>
            </measure></part></score-partwise>
        """.trimIndent().toByteArray()
        val parsed = MusicXml.parse(xml)
        assertEquals(listOf(0 to 0, 40 to 129), parsed.notes.map { it.program to it.bank })
        assertEquals(listOf(2, 9), parsed.notes.map { it.channel })
        assertEquals(listOf(false, true), parsed.notes.map { it.percussion })
        assertEquals(2, parsed.parts.single().channel)
        assertEquals(2, parsed.parts.single().instruments.size)
        val routing = AudioRouting(parsed)
        assertEquals(listOf(0, 0), parsed.notes.map(routing::noteChannel))
        assertEquals(listOf(0), routing.controls.map { it.channel }.distinct())
        assertEquals(0, routing.basePrograms[0]); assertEquals(0, routing.baseBanks[0])
        val routedOverride = routing.mix(listOf(true), listOf(100), listOf(0), listOf(true))
        assertEquals(0, routedOverride.programs[0]); assertTrue(routedOverride.overrides[0])
    }
    @Test fun performanceContextUsesTraditionalChineseAndExpandedScoreSemantics() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><dynamics><mf/></dynamics><wedge type='crescendo'/><pedal type='start'/></direction-type></direction>" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><notations><ornaments><trill-mark/></ornaments></notations></note>" +
            "<note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><notations><glissando type='start' number='3'/></notations></note></measure>" +
            "<measure number='2'><direction><direction-type><wedge type='stop'/></direction-type></direction>" +
            "<note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><notations><glissando type='stop' number='3'/></notations></note></measure>"))
        assertTrue(parsed.contexts.any { it.code == "dynamic:mf" })
        assertTrue(parsed.contexts.any { it.code == "trill-mark" })
        assertTrue(parsed.contexts.any { it.code == "crescendo" })
        assertTrue(parsed.contexts.any { it.code == "glissando" })
        val opening = PerformanceDisplay.context(parsed, 120, listOf(true), listOf(0), listOf(false))
        assertTrue("中強" in opening); assertTrue("漸強" in opening); assertTrue("顫音" in opening)
        assertTrue("延音踏板" in opening)
        assertTrue("滑音" in PerformanceDisplay.context(parsed, 1200, listOf(true), listOf(0), listOf(false)))
    }
    @Test fun scopedDynamicsCarryAcrossMeasuresAndDisplaySimultaneousValues() {
        fun voiced(step: String, voice: Int, staff: Int) =
            "<note><pitch><step>$step</step><octave>4</octave></pitch><duration>4</duration><voice>$voice</voice><staff>$staff</staff></note>"
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes" +
                "<direction><direction-type><dynamics><mf/></dynamics></direction-type><voice>1</voice><staff>1</staff></direction>" +
                voiced("C", 1, 1) + "<backup><duration>4</duration></backup>" + voiced("G", 2, 2) + "</measure>" +
            "<measure number='2'><direction><direction-type><dynamics><f/></dynamics></direction-type></direction>" +
                voiced("D", 1, 1) + "<backup><duration>4</duration></backup>" + voiced("A", 2, 2) + "</measure>" +
            "<measure number='3'><direction><direction-type><dynamics><p/></dynamics></direction-type><voice>1</voice><staff>1</staff></direction>" +
                voiced("E", 1, 1) + "<backup><duration>4</duration></backup>" + voiced("B", 2, 2) + "</measure>"))
        assertEquals(listOf(85, 88, 100, 100, 55, 100), parsed.notes.map { it.velocity })
        assertTrue(parsed.contexts.any { it.code == "dynamic:p" && it.voice == "1" && it.staff == 1 })
        assertTrue(parsed.contexts.any { it.code == "dynamic:f" && it.voice == "2" && it.staff == 2 })
        val labels = PerformanceDisplay.context(parsed, 3840, listOf(true), listOf(0), listOf(false)).split("・")
        assertEquals(1, labels.count { it == "弱" })
        assertEquals(1, labels.count { it == "強" })
    }
    @Test fun actualInstrumentLabelsFollowScoreChangesAndUserOverrides() {
        val parsed = MusicXml.parse("""
            <score-partwise version="4.0"><part-list><score-part id="P"><part-name>Mixed</part-name>
            <midi-instrument id="piano"><midi-program>1</midi-program><midi-channel>1</midi-channel></midi-instrument>
            <midi-instrument id="flute"><midi-program>74</midi-program><midi-channel>2</midi-channel></midi-instrument>
            </score-part></part-list><part id="P"><measure number="1">$attributes
            <note><instrument id="piano"/><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>
            <note><instrument id="flute"/><pitch><step>D</step><octave>4</octave></pitch><duration>3</duration></note>
            </measure></part></score-partwise>
        """.trimIndent().toByteArray())
        assertEquals(listOf("平台鋼琴"), PerformanceDisplay.instrumentLabels(parsed, 0, listOf(0), listOf(false)))
        assertEquals(listOf("長笛"), PerformanceDisplay.instrumentLabels(parsed, 480, listOf(0), listOf(false)))
        assertEquals(listOf("小提琴"), PerformanceDisplay.instrumentLabels(parsed, 480, listOf(40), listOf(true)))
        assertTrue("音色：長笛" in PerformanceDisplay.context(parsed, 480, listOf(true), listOf(0), listOf(false)))
    }
    @Test fun pedalKindsKeepHalfValuesAndTempoRampsRequireAnchorsAndRepeat() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes<barline><repeat direction='forward'/></barline>" +
            "<direction><direction-type><pedal type='start' pedal-type='sostenuto' value='63'/><pedal type='start' pedal-type='soft' value='47'/></direction-type>" +
            "<sound damper-pedal='64'/></direction>${note(duration = 4)}<barline><repeat direction='backward'/></barline></measure>"))
        assertEquals(listOf(0, 1920), parsed.controls.filter { it.controller == 64 && it.to == 81 }.map { it.tick })
        assertEquals(listOf(63, 63), parsed.controls.filter { it.controller == 66 && it.to > 0 }.map { it.to })
        assertEquals(listOf(47, 47), parsed.controls.filter { it.controller == 67 && it.to > 0 }.map { it.to })

        val ramp = MusicXml.parse(score("<measure number='1'>$attributes<direction><sound tempo='120'/></direction>" +
            "<direction><direction-type><words>rit.</words></direction-type></direction>${note(duration = 4)}</measure>" +
            "<measure number='2'><direction><sound tempo='60'/></direction>${note("D", 4)}</measure>"))
        assertTrue(ramp.tempos.any { it.tick in 1..1919 && it.bpm in 60.0..120.0 })
        val noAnchor = MusicXml.parse(score("<measure number='1'>$attributes<direction><direction-type><words>accelerando</words></direction-type></direction>${note(duration = 4)}</measure>"))
        assertTrue(noAnchor.warnings.any { it.contains("tempo anchor") })
        assertEquals(listOf(120.0), noAnchor.tempos.map { it.bpm }.distinct())
    }
    @Test fun slurAvoidsSameMidiNoteCollisionButOverlapsDifferentPitch() {
        fun slurred(second: String) = MusicXml.parse(score("<measure number='1'>$attributes" +
            note(duration = 1, extra = "<notations><slur type='start' number='8'/></notations>") +
            "<note><pitch><step>$second</step><octave>4</octave></pitch><duration>3</duration><notations><slur type='stop' number='8'/></notations></note></measure>"))
        val repeated = slurred("C")
        assertEquals(480, repeated.notes[0].duration)
        assertEquals(repeated.notes[1].tick, repeated.notes[0].tick + repeated.notes[0].duration)
        val moving = slurred("D")
        assertEquals(504, moving.notes[0].duration)
        assertTrue(moving.notes[0].tick + moving.notes[0].duration > moving.notes[1].tick)
    }
    @Test fun pendingGraceCrossesMeasureAndKeepsItsOwnInstrumentIdentity() {
        val xml = """
            <score-partwise version="4.0"><part-list><score-part id="P"><part-name>Grace</part-name>
            <midi-instrument id="main"><midi-program>1</midi-program><midi-channel>2</midi-channel></midi-instrument>
            <midi-instrument id="grace"><midi-program>42</midi-program><midi-bank>8</midi-bank><midi-channel>6</midi-channel></midi-instrument>
            </score-part></part-list><part id="P">
            <measure number="1">$attributes${note(duration = 4)}
            <note><grace slash="yes"/><instrument id="grace"/><pitch><step>D</step><octave>4</octave></pitch></note></measure>
            <measure number="2"><note><instrument id="main"/><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration></note></measure>
            </part></score-partwise>
        """.trimIndent().toByteArray()
        val parsed = MusicXml.parse(xml)
        val grace = parsed.notes.first { it.pitch == 62 }
        assertEquals(41, grace.program)
        assertEquals(7, grace.bank)
        assertEquals(5, grace.channel)
        assertEquals("grace", grace.instrument)
        assertEquals(1920, grace.tick)
        assertFalse(parsed.warnings.any { it.contains("裝飾音沒有") })
    }
    @Test fun metricModulationUsesTempoAtItsTickRatherThanDocumentOrder() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><offset>2</offset><sound tempo='60'/></direction>" +
            "<direction><direction-type><metronome><beat-unit>quarter</beat-unit><beat-unit>half</beat-unit></metronome></direction-type></direction>" +
            "${note(duration = 4)}</measure>"))
        assertTrue(parsed.tempos.any { it.tick == 0 && it.bpm == 240.0 })
        assertTrue(parsed.tempos.any { it.tick == 960 && it.bpm == 60.0 })
    }
    @Test fun swingChangesAtNextPairBoundaryWithoutRewritingCurrentPair() {
        val parsed = MusicXml.parse(score("<measure number='1'><attributes><divisions>2</divisions><time><beats>2</beats><beat-type>4</beat-type></time></attributes>" +
            "<direction><direction-type><swing><first>2</first><second>1</second><swing-type>eighth</swing-type></swing></direction-type></direction>" +
            note(duration = 1) +
            "<direction><direction-type><swing><straight/><swing-type>eighth</swing-type></swing></direction-type></direction>" +
            note("D", 1) + note("E", 1) + note("F", 1) + "</measure>"))
        assertEquals(listOf(0, 320, 480, 720), parsed.notes.map { it.tick })
        assertEquals(listOf(320, 160, 240, 240), parsed.notes.map { it.duration })
    }
    @Test fun wavyLineDoesNotLeakAcrossVoiceStaffOrInstrument() {
        fun voiceNote(step: String, voice: Int, staff: Int, instrument: String, wavy: String = "") =
            "<note><instrument id='$instrument'/><pitch><step>$step</step><octave>4</octave></pitch><duration>1</duration>" +
                "<voice>$voice</voice><staff>$staff</staff>$wavy</note>"
        val xml = """
            <score-partwise version="4.0"><part-list><score-part id="P"><part-name>Voices</part-name>
            <midi-instrument id="I1"><midi-program>1</midi-program></midi-instrument>
            <midi-instrument id="I2"><midi-program>2</midi-program></midi-instrument></score-part></part-list><part id="P">
            <measure number="1">$attributes
            ${voiceNote("C", 1, 1, "I1", "<notations><ornaments><wavy-line type='start' number='2'/></ornaments></notations>")}
            <backup><duration>1</duration></backup>${voiceNote("G", 2, 2, "I2")}</measure>
            <measure number="2">${voiceNote("A", 2, 2, "I2")}<backup><duration>1</duration></backup>
            ${voiceNote("D", 1, 1, "I1", "<notations><ornaments><wavy-line type='stop' number='2'/></ornaments></notations>")}</measure>
            </part></score-partwise>
        """.trimIndent().toByteArray()
        val parsed = MusicXml.parse(xml)
        assertEquals(2, parsed.notes.count { it.voice == "2" })
        assertEquals(listOf(67, 69), parsed.notes.filter { it.voice == "2" }.map { it.pitch })
        assertTrue(parsed.notes.count { it.voice == "1" } > 2)
    }
    @Test fun shortGlissandoOnlyEmitsLevelsThatFitBeforeTarget() {
        val parsed = MusicXml.parse(score("<measure number='1'><attributes><divisions>480</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>" +
            "<note><pitch><step>C</step><octave>4</octave></pitch><duration>3</duration><notations><glissando type='start' number='1'>chromatic</glissando></notations></note>" +
            "<note><pitch><step>C</step><octave>5</octave></pitch><duration>477</duration><notations><glissando type='stop' number='1'/></notations></note></measure>"))
        assertEquals(listOf(0, 1, 2, 3), parsed.notes.take(4).map { it.tick })
        assertEquals(4, parsed.notes.count { it.tick <= 3 })
        assertTrue(parsed.notes.filter { it.pitch !in setOf(60, 72) }.all { it.tick < 3 })
    }
    @Test fun soundPedalsUsePercentWhilePedalElementUsesMidiRange() {
        val parsed = MusicXml.parse(score("<measure number='1'>$attributes" +
            "<direction><direction-type><pedal type='start' value='50'/></direction-type>" +
            "<sound damper-pedal='50' sostenuto-pedal='25' soft-pedal='100'/></direction>${note(duration = 4)}</measure>"))
        assertTrue(parsed.controls.any { it.controller == 64 && it.to == 50 })
        assertTrue(parsed.controls.any { it.controller == 64 && it.to == 64 })
        assertTrue(parsed.controls.any { it.controller == 66 && it.to == 32 })
        assertTrue(parsed.controls.any { it.controller == 67 && it.to == 127 })
    }
    @Test fun parserMarksAreClearedFromPublicScoreEquality() {
        val xml = score("<measure number='1'>$attributes${note(duration = 4, extra = "<notations><slur type='start'/><slur type='stop'/></notations>")}</measure>")
        val first = MusicXml.parse(xml)
        val second = MusicXml.parse(xml)
        assertTrue(first.notes.all { it.marks.isEmpty() })
        assertEquals(first, second)
    }
    @Test fun allRestScorePreservesBarsEndTickAndTempo() {
        val parsed = MusicXml.parse(score(
            "<measure number='1'>$attributes<note><rest/><duration>4</duration></note></measure>" +
            "<measure number='2'><direction><sound tempo='60'/></direction><note><rest/><duration>4</duration></note></measure>"))
        assertTrue(parsed.notes.isEmpty())
        assertEquals(listOf("1", "2"), parsed.bars.map { it.number })
        assertEquals(listOf(0, 1920), parsed.bars.map { it.tick })
        assertEquals(3840, parsed.endTick)
        assertTrue(parsed.tempos.any { it.tick == 1920 && it.bpm == 60.0 })
        assertEquals(6000, parsed.millisAt(parsed.endTick))
    }
    @Test fun scoreTimelineMatchesHalfOpenReferenceQueriesAtBoundaries() {
        val parts = listOf(Part("P1", "One", 0, false), Part("P2", "Two", 40, false))
        val notes = listOf(
            Note(0, 10, 60, 80, 0), Note(5, 15, 64, 80, 1), Note(10, 10, 67, 80, 0), Note(20, 1, 69, 80, 0)
        )
        val contexts = listOf(
            PerformanceContext(0, 10, 0, "dynamic:p"), PerformanceContext(10, 30, 0, "dynamic:f"),
            PerformanceContext(5, 20, 1, "legato")
        )
        val controls = listOf(Control(0, 0, 0, 64, 127), Control(8, 18, 0, 11, 80, 110), Control(10, 10, 0, 64, 0))
        val score = Score("Indexed", "", parts, notes, listOf(Tempo(0, 120.0)),
            listOf(Bar("1", 0, 10), Bar("2", 10, 10), Bar("3", 20, 10)), 30, emptyList(), controls, contexts)
        listOf(0, 4, 5, 9, 10, 17, 18, 19, 20, 21, 30).forEach { tick ->
            assertEquals(notes.filter { it.tick <= tick && tick < it.tick + it.duration }, score.timeline.activeNotes(tick))
            assertEquals(contexts.filter { it.tick <= tick && tick < it.endTick }, score.timeline.activeContexts(tick))
            assertEquals(controls.filter { it.endTick > it.tick && it.tick <= tick && tick < it.endTick }, score.timeline.activeControls(tick))
            assertEquals(score.bars.lastOrNull { it.tick <= tick }, score.timeline.barAt(tick))
        }
        listOf(0 to 10, 1 to 5, 10 to 20, 20 to 21, 21 to 30).forEach { (start, end) ->
            assertEquals(notes.filter { it.tick < end && it.tick + it.duration > start }, score.timeline.visibleNotes(start, end))
        }
        assertEquals(notes.filter { it.part == 0 && it.tick == 10 }, score.timeline.latestOnset(0, 19))
        assertEquals("dynamic:f", score.timeline.dynamicContext(0, 30)?.code)
        assertEquals(0, score.timeline.latestControl(0, 64, 10)?.to)
        assertEquals(listOf("2", "3"), score.timeline.visibleBars(10, 20).map { it.number })
    }
    @Test fun providedMozartFileImportsAsPianoWithTwoVoicesAndReopens() {
        val path = System.getenv("MUSICXML_IMPORT_FIXTURE")
        assumeTrue("Set MUSICXML_IMPORT_FIXTURE to validate the original imported file", !path.isNullOrBlank())
        val bytes = File(path!!).readBytes()
        val parsed = MusicXml.parse(bytes)
        assertEquals("Piano", parsed.parts.single().name)
        assertEquals(0, parsed.parts.single().program)
        assertTrue(parsed.composer.contains("Mozart"))
        assertTrue(parsed.notes.any { it.voice == "1" })
        assertTrue(parsed.notes.any { it.voice == "5" })
        assertEquals(480, parsed.bars.first().duration)
        assertTrue(parsed.bars.count { it.number == "0" } >= 2)
        assertEquals(90, parsed.bars.size)
        assertEquals(listOf("0", "1", "2"), parsed.bars.takeLast(18).take(3).map { it.number })
        assertEquals("16", parsed.bars.last().number)
        assertTrue(parsed.endTick > 0)
        assertEquals(parsed, MusicXml.parse(MusicXml.normalizedXml(bytes)))
    }
}
