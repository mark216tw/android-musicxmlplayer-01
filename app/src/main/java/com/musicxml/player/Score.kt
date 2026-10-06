package com.musicxml.player

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.pow
import kotlin.math.roundToInt

const val PPQ = 480
data class Note(val tick: Int, val duration: Int, val pitch: Int, val velocity: Int, val part: Int,
                val voice: String = "1", val staff: Int = 1, val instrument: String = "",
                val program: Int = -1, val bank: Int = -1, val channel: Int = -1,
                val percussion: Boolean = false, @Transient internal val marks: Set<String> = emptySet())
data class Control(val tick: Int, val endTick: Int, val part: Int, val controller: Int,
                   val from: Int, val to: Int = from)
data class InstrumentSpec(val id: String, val program: Int, val bank: Int, val percussion: Boolean = false,
                          val channel: Int = -1)
data class Part(val id: String, val name: String, val program: Int, val percussion: Boolean,
                val bank: Int = 0, val instruments: List<InstrumentSpec> = emptyList(), val channel: Int = -1)
data class Tempo(val tick: Int, val bpm: Double)
data class Bar(val number: String, val tick: Int, val duration: Int,
               val sourceMeasure: Int = -1, val occurrence: Int = 1)
data class PerformanceContext(val tick: Int, val endTick: Int, val part: Int, val code: String,
                               val value: Int? = null, val voice: String = "", val staff: Int = 0)
data class Score(val title: String, val composer: String, val parts: List<Part>, val notes: List<Note>,
                  val tempos: List<Tempo>, val bars: List<Bar>, val endTick: Int, val warnings: List<String>,
                   val controls: List<Control> = emptyList(),
                   val contexts: List<PerformanceContext> = emptyList()) {
    val timeline: ScoreTimeline by lazy { ScoreTimeline(this) }
    private val orderedTempos = (listOf(Tempo(0, 120.0)) + tempos).sortedBy { it.tick }
    private val elapsed = DoubleArray(orderedTempos.size).apply {
        for (i in 1 until size) this[i] = this[i - 1] +
            (orderedTempos[i].tick - orderedTempos[i - 1].tick) * 60000.0 / (PPQ * orderedTempos[i - 1].bpm)
    }
    fun millisAt(tick: Int): Int {
        var low = 0
        var high = orderedTempos.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (orderedTempos[mid].tick <= tick) low = mid else high = mid - 1
        }
        val tempo = orderedTempos[low]
        return (elapsed[low] + (tick - tempo.tick) * 60000.0 / (PPQ * tempo.bpm)).toInt()
    }
    fun tickAt(millis: Int): Int {
        var low = 0
        var high = endTick
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (millisAt(mid) <= millis) low = mid else high = mid - 1
        }
        return low
    }
}

private fun Element.children(tag: String? = null): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { tag == null || it.tagName == tag }
private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
private fun Element.text(tag: String): String = child(tag)?.textContent?.trim().orEmpty()
private fun Element.int(tag: String, default: Int = 0): Int = text(tag).toIntOrNull() ?: default

object MusicXml {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 1024
    private const val MAX_ZIP_NAME_LENGTH = 1024
    private const val MAX_TICK = 10_000_000
    private const val MAX_ENDING_NUMBER = 32
    private const val MAX_EVENTS = 500_000
    private const val MAX_PLAYBACK_MILLIS = 30 * 60 * 1000
    private const val PLAYBACK_MILLIS_TOLERANCE = 0.5

    private class ResourceBudget {
        private var sourceEvents = 0L
        private var expandedEvents = 0L

        fun reserveSource(count: Long, source: String) {
            require(count >= 0 && sourceEvents + count <= MAX_EVENTS) {
                "$source 產生的解析事件過多（上限 $MAX_EVENTS），已停止解析"
            }
            sourceEvents += count
        }

        fun releaseSource(count: Long) {
            sourceEvents = (sourceEvents - count).coerceAtLeast(0)
        }

        fun reserveExpanded(count: Long, source: String) {
            require(count >= 0 && expandedEvents + count <= MAX_EVENTS) {
                "$source 展開後的演奏事件過多（上限 $MAX_EVENTS），已停止解析"
            }
            expandedEvents += count
        }
    }

    private fun checkedTick(value: Long, label: String): Int {
        require(value in 0..MAX_TICK.toLong()) { "$label 的 tick 超出安全範圍（上限 $MAX_TICK）" }
        return value.toInt()
    }

    private fun checkedAddTick(left: Int, right: Int, label: String): Int =
        checkedTick(left.toLong() + right.toLong(), label)

    private fun durationUnits(element: Element, label: String): Long {
        val value = element.text("duration")
        if (value.isBlank()) return 0
        val parsed = value.toLongOrNull()
        require(parsed != null && parsed >= 0) { "$label 的 duration 必須是非負整數" }
        return parsed
    }

    private fun scaledTicks(units: Long, divisions: Int, label: String): Double {
        val value = units.toDouble() * PPQ / divisions
        require(value.isFinite() && value <= MAX_TICK) {
            "$label 的 duration 過大，tick 超出安全範圍（上限 $MAX_TICK）"
        }
        return value
    }

    private fun scaledOffset(element: Element, divisions: Int): Double {
        val text = element.text("offset")
        if (text.isBlank()) return 0.0
        val units = text.toLongOrNull()
        require(units != null) { "控制事件的 offset 必須是整數" }
        val value = units.toDouble() * PPQ / divisions
        require(value.isFinite() && value in -MAX_TICK.toDouble()..MAX_TICK.toDouble()) {
            "控制事件的 tick 超出安全範圍（上限 $MAX_TICK）"
        }
        return value
    }

    private fun checkedExactTick(value: Double, label: String): Double {
        require(value.isFinite() && value in 0.0..MAX_TICK.toDouble()) {
            "$label 的 tick 超出安全範圍（上限 $MAX_TICK）"
        }
        return value
    }

    private fun expandedDurationMillis(endTick: Int, tempos: List<Tempo>): Double {
        val ordered = (listOf(Tempo(0, 120.0)) + tempos).filter { it.tick <= endTick }.sortedBy { it.tick }
        var elapsed = 0.0
        for (index in ordered.indices) {
            val start = ordered[index].tick.coerceIn(0, endTick)
            val end = (ordered.getOrNull(index + 1)?.tick ?: endTick).coerceIn(start, endTick)
            elapsed += (end.toLong() - start.toLong()) * 60000.0 / (PPQ * ordered[index].bpm)
        }
        return elapsed
    }

    private fun document(bytes: ByteArray): Element {
        require(bytes.size <= MAX_BYTES) { "樂譜超過 20 MB" }
        // Android's JAXP parser does not implement desktop Xerces feature switches.
        // Reject DTDs before parsing and deny every external entity at the resolver.
        require(!Regex("<!DOCTYPE", RegexOption.IGNORE_CASE).containsMatchIn(decodeXml(bytes))) { "不支援內嵌 DTD 或 XML 實體宣告" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }
    fun unpack(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return bytes
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "MXL ZIP 項目超過 $MAX_ZIP_ENTRIES 個" }
                require(entry.name.length <= MAX_ZIP_NAME_LENGTH) { "MXL ZIP 檔名超過 $MAX_ZIP_NAME_LENGTH 個字元" }
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    var count = zip.read(buffer)
                    while (count != -1) {
                        total += count
                        require(total <= MAX_BYTES) { "解壓後樂譜超過 20 MB" }
                        output.write(buffer, 0, count)
                        count = zip.read(buffer)
                    }
                    files[entry.name] = output.toByteArray()
                }
                entry = zip.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]
        val rootPath = container?.let {
            val roots = document(it).getElementsByTagName("rootfile")
            (0 until roots.length).map { index -> roots.item(index) as Element }
                .firstOrNull { root -> root.getAttribute("media-type") == "application/vnd.recordare.musicxml+xml" }
                ?.getAttribute("full-path")
                ?: (roots.item(0) as? Element)?.getAttribute("full-path")
        }
        return (if (rootPath != null) files[rootPath] else files.entries.firstOrNull {
            !it.key.startsWith("META-INF/") && (it.key.endsWith(".xml") || it.key.endsWith(".musicxml"))
        }?.value) ?: error("MXL 找不到主樂譜")
    }

    private fun decodeXml(raw: ByteArray): String {
        val encoding = when {
            raw.size >= 2 && raw[0] == 0xff.toByte() && raw[1] == 0xfe.toByte() -> "UTF-16"
            raw.size >= 2 && raw[0] == 0xfe.toByte() && raw[1] == 0xff.toByte() -> "UTF-16"
            raw.size >= 2 && raw[0] == 60.toByte() && raw[1] == 0.toByte() -> "UTF-16LE"
            raw.size >= 2 && raw[0] == 0.toByte() && raw[1] == 60.toByte() -> "UTF-16BE"
            else -> Regex("^\\s*<\\?xml\\b[^>]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                .find(raw.take(256).toByteArray().toString(Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "UTF-8"
        }
        return raw.toString(java.nio.charset.Charset.forName(encoding)).removePrefix("\uFEFF")
    }
    fun normalizedXml(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_BYTES) { "樂譜超過 20 MB" }
        val raw = unpack(bytes)
        require(raw.size <= MAX_BYTES) { "樂譜超過 20 MB" }
        val content = decodeXml(raw).trimStart()
        val xml = if (content.startsWith("{") || content.startsWith("[")) {
            val wrapper = try {
                val tokener = JSONTokener(content)
                val value = tokener.nextValue()
                require(tokener.nextClean() == '\u0000') { "JSON 樂譜格式錯誤：結尾含有多餘內容" }
                value as? JSONObject ?: error("JSON 樂譜必須是包含 musicxml 欄位的物件")
            } catch (e: JSONException) {
                throw IllegalArgumentException("JSON 樂譜格式錯誤：${e.message}", e)
            }
            require(wrapper.has("musicxml")) { "JSON 樂譜缺少 musicxml 欄位" }
            val embedded = wrapper.opt("musicxml")
            require(embedded is String) { "JSON 樂譜的 musicxml 欄位必須是 XML 字串" }
            embedded.removePrefix("\uFEFF").trim().also {
                require(it.isNotEmpty()) { "JSON 樂譜的 musicxml 欄位不可為空" }
                require(it.startsWith("<")) { "JSON 樂譜的 musicxml 欄位不是 XML 內容" }
            }
        } else content
        return xml.replace(Regex("(<\\?xml[^>]*encoding\\s*=\\s*)['\"][^'\"]+['\"]", RegexOption.IGNORE_CASE), "$1\"UTF-8\"").toByteArray()
    }

    private enum class NavigationKind { DA_CAPO, DAL_SEGNO, SEGNO, TO_CODA, CODA, FINE }
    private data class Navigation(val tick: Int, val kind: NavigationKind, val target: String = "")
    private data class DynamicEvent(val tick: Int, val value: Int, val staff: Int = 0, val voice: String = "",
                                    val code: String = "level", val order: Int = 0)
    private data class WedgeMark(val tick: Int, val type: String, val number: String,
                                 val staff: Int = 0, val voice: String = "", val niente: Boolean = false)
    private data class LocalControl(val tick: Int, val endTick: Int, val controller: Int,
                                    val from: Int, val to: Int = from)
    private data class SwingEvent(val tick: Int, val first: Int, val second: Int, val unit: Int,
                                  val straight: Boolean = false)
    private data class TempoHint(val tick: Int)
    private data class TempoRequest(val tick: Int, val absolute: Double? = null, val ratio: Double? = null)
    private data class TimingInsertion(val tick: Int, val duration: Int)
    private data class Measure(val number: String, val length: Int, val notes: List<Note>,
                               val tempos: List<Tempo>, val forward: Boolean, val repeat: Int,
                               val endings: Set<Int>, val navigation: List<Navigation>,
                                val dynamics: List<DynamicEvent>, val wedges: List<WedgeMark>,
                                 val controls: List<LocalControl>, val insertions: List<TimingInsertion>,
                                 val swings: List<SwingEvent>, val tempoHints: List<TempoHint>,
                                 val tempoRequests: List<TempoRequest>)
    private data class RepeatRegion(val start: Int, val end: Int, val times: Int)
    private data class RepeatFrame(val region: RepeatRegion, var pass: Int = 1)
    private data class TieKey(val continuity: Int, val part: Int, val pitch: Int, val voice: String,
                              val staff: Int, val instrument: String)
    private data class WedgeKey(val number: String, val staff: Int, val voice: String)
    private data class WavyKey(val number: String, val voice: String, val staff: Int, val instrument: String)
    private data class GraceNote(val pitch: Int, val slash: Boolean, val stealFollowing: Double?,
                                 val stealPrevious: Double?, val makeTime: Double?, val voice: String,
                                 val staff: Int, val instrument: String, val chord: Boolean,
                                 val program: Int, val bank: Int, val channel: Int, val percussion: Boolean)
    private data class PendingTremolo(val owner: MutableList<Note>, val note: Note, val marks: Int)
    private data class PriorGroup(val owner: MutableList<Note>, val notes: List<Note>, val start: Int,
                                  val end: Int, val duration: Int, val measureStart: Int)

    private const val TIE_START = 256
    private const val TIE_STOP = 512
    private const val BREATH = 1024
    private const val CAESURA = 2048

    private fun dynamicVelocity(tag: String): Int? = mapOf(
        "pppp" to 20, "ppp" to 28, "pp" to 40, "p" to 55, "mp" to 70, "mf" to 85,
        "f" to 100, "ff" to 115, "fff" to 125, "ffff" to 127,
        "fp" to 72, "sf" to 108, "sfp" to 92, "sfpp" to 78, "sfz" to 112, "sffz" to 120,
        "fz" to 108, "rf" to 106, "rfz" to 110
    )[tag]
    private fun ticks(duration: Int, divisions: Int) = (duration.toDouble() * PPQ / divisions).roundToInt()

    private fun beatUnit(name: String): Double? = mapOf(
        "maxima" to 32.0, "long" to 16.0, "breve" to 8.0, "whole" to 4.0,
        "half" to 2.0, "quarter" to 1.0, "eighth" to .5, "16th" to .25,
        "32nd" to .125, "64th" to .0625, "128th" to .03125,
        "256th" to .015625, "512th" to .0078125, "1024th" to .00390625
    )[name]

    private fun metronomeTempo(metronome: Element): Pair<Double?, Double?> {
        data class Beat(val value: Double)
        val beats = mutableListOf<Beat>()
        var current: Double? = null
        var dots = 0
        fun finish() {
            current?.let { beats += Beat(it * (2.0 - 2.0.pow(-dots))) }
            current = null; dots = 0
        }
        metronome.children().forEach { child -> when (child.tagName) {
            "beat-unit" -> { finish(); current = beatUnit(child.textContent.trim()) }
            "beat-unit-dot" -> if (current != null) dots++
        } }
        finish()
        val perMinute = metronome.text("per-minute").toDoubleOrNull()
        return when {
            perMinute != null && beats.isNotEmpty() -> perMinute * beats.first().value to null
            perMinute != null -> perMinute to null
            beats.size >= 2 -> null to (beats[1].value / beats[0].value)
            else -> null to null
        }
    }

    private fun pedalElementValue(value: String): Int = when (value.trim().lowercase()) {
        "yes", "true", "start", "resume" -> 127
        "no", "false", "stop", "discontinue" -> 0
        else -> value.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 127) ?: 0
    }

    private fun soundPedalValue(value: String): Int = when (value.trim().lowercase()) {
        "yes", "true", "start", "resume" -> 127
        "no", "false", "stop", "discontinue" -> 0
        else -> ((value.toDoubleOrNull() ?: 0.0) * 1.27).roundToInt().coerceIn(0, 127)
    }

    private fun realizePartSemantics(source: List<Measure>, warnings: MutableSet<String>, budget: ResourceBudget): List<Measure> {
        if (source.isEmpty()) return source
        val starts = IntArray(source.size)
        for (i in 1 until source.size) starts[i] = checkedAddTick(starts[i - 1], source[i - 1].length, "聲部")
        data class Located(val measure: Int, var note: Note)
        val located = source.flatMapIndexed { i, measure ->
            measure.notes.map { Located(i, it.copy(tick = starts[i] + it.tick)) }
        }.toMutableList()

        located.groupBy { it.note.tick }.values.forEach { onset ->
            val arpeggios = onset.mapNotNull { item ->
                item.note.marks.firstOrNull { it.startsWith("arp:") }?.split(':')?.let { item to it }
            }.groupBy { it.second.getOrElse(1) { "1" } }
            arpeggios.values.forEach { group ->
                val number = group.first().second.getOrElse(1) { "1" }
                if (onset.any { item -> item.note.marks.any { it == "noarp:$number" } }) return@forEach
                val playable = group.filterNot { (item, _) -> item.note.marks.any { it.startsWith("noarp:") } }
                if (playable.size < 2) return@forEach
                val direction = playable.first().second.getOrElse(2) { "up" }
                val ordered = if (direction == "down") playable.sortedByDescending { it.first.note.pitch }
                    else playable.sortedBy { it.first.note.pitch }
                val window = minOf(PPQ / 8, ordered.minOf { it.first.note.duration } / 4).coerceAtLeast(1)
                ordered.forEachIndexed { index, (item, _) ->
                    val originalEnd = item.note.tick + item.note.duration
                    val offset = if (ordered.size == 1) 0 else window * index / (ordered.size - 1)
                    item.note = item.note.copy(tick = item.note.tick + offset,
                        duration = (originalEnd - item.note.tick - offset).coerceAtLeast(1),
                        marks = item.note.marks + "context:arpeggio")
                }
            }
        }

        val ordered = located.sortedWith(compareBy<Located> { it.note.tick }.thenBy { it.note.pitch })
        val activeSlurs = mutableSetOf<String>()
        val previous = mutableMapOf<String, List<Located>>()
        ordered.groupBy { it.note.tick }.toSortedMap().forEach { (_, group) ->
            val startsHere = group.flatMap { it.note.marks }.filter { it.startsWith("slur:start:") }.map { it.substringAfterLast(':') }
            val stopsHere = group.flatMap { it.note.marks }.filter { it.startsWith("slur:stop:") }.map { it.substringAfterLast(':') }
            activeSlurs += startsHere
            activeSlurs.forEach { number ->
                group.forEach { it.note = it.note.copy(marks = it.note.marks + "context:legato") }
                previous[number]?.forEach { prior ->
                    val next = group.minOf { it.note.tick }
                    if (prior.note.velocity and (TIE_START or TIE_STOP) == 0 && next >= prior.note.tick + prior.note.duration) {
                        val sameMidiNote = group.any { it.note.channel == prior.note.channel && it.note.pitch == prior.note.pitch }
                        val overlap = if (sameMidiNote) 0 else minOf(PPQ / 20, 24)
                        prior.note = prior.note.copy(duration = next - prior.note.tick + overlap,
                            marks = prior.note.marks + "carry")
                    }
                }
                previous[number] = group
            }
            stopsHere.forEach { activeSlurs.remove(it); previous.remove(it) }
        }

        val glissMarks = ordered.flatMap { item -> item.note.marks.filter { it.startsWith("gliss:") }.map { item to it.split(':') } }
            .groupBy { it.second.getOrElse(2) { "1" } }
        glissMarks.forEach { (number, marks) ->
            val pending = ArrayDeque<Pair<Located, List<String>>>()
            marks.sortedBy { it.first.note.tick }.forEach { mark ->
                when (mark.second.getOrElse(1) { "" }) {
                    "start" -> pending.addLast(mark)
                    "stop" -> {
                        val start = pending.removeFirstOrNull()
                        if (start == null) warnings += "滑音 $number 沒有可配對的 start"
                        else {
                            val from = start.first.note
                            val to = mark.first.note
                            val ambiguous = ordered.count { it.note.tick == from.tick && it.note.marks.any { m -> m.startsWith("gliss:start:$number") } } != 1 ||
                                ordered.count { it.note.tick == to.tick && it.note.marks.any { m -> m.startsWith("gliss:stop:$number") } } != 1
                            if (to.tick <= from.tick || ambiguous) warnings += "滑音 $number 的和弦端點無法安全配對，已保留原音"
                            else {
                                val direction = if (to.pitch > from.pitch) 1 else -1
                                val context = "context:${start.second.getOrElse(3) { "glissando" }}"
                                start.first.note = from.copy(marks = from.marks + context)
                                val white = start.second.getOrElse(4) { "" }.contains("white", true)
                                val candidates = generateSequence(from.pitch + direction) { it + direction }
                                    .takeWhile { if (direction > 0) it < to.pitch else it > to.pitch }
                                    .filter { !white || Math.floorMod(it, 12) in setOf(0, 2, 4, 5, 7, 9, 11) }.toList()
                                val span = to.tick - from.tick
                                val count = minOf(candidates.size, (span - 1).coerceAtLeast(0))
                                val pitches = if (count == candidates.size) candidates else (0 until count).map { index ->
                                    candidates[((index + 1) * (candidates.size + 1) / (count + 1) - 1).coerceIn(candidates.indices)]
                                }
                                if (pitches.isNotEmpty()) {
                                    budget.reserveSource(pitches.size.toLong(), "滑音")
                                    val firstStep = from.tick + span / (pitches.size + 1)
                                    start.first.note = from.copy(duration = minOf(from.duration, firstStep - from.tick).coerceAtLeast(1),
                                        marks = from.marks + context)
                                    pitches.forEachIndexed { index, pitch ->
                                        val at = from.tick + span * (index + 1) / (pitches.size + 1)
                                        val nextAt = from.tick + span * (index + 2) / (pitches.size + 1)
                                        located += Located(start.first.measure, from.copy(tick = at,
                                            duration = minOf(nextAt - at, to.tick - at).coerceAtLeast(1), pitch = pitch,
                                            velocity = from.velocity and 255, marks = setOf(context)))
                                    }
                                } else if (span > 0) {
                                    start.first.note = from.copy(duration = minOf(from.duration, span).coerceAtLeast(1),
                                        marks = from.marks + context)
                                }
                            }
                        }
                    }
                }
            }
            if (pending.isNotEmpty()) warnings += "滑音 $number 沒有可配對的 stop"
        }

        val result = source.map { it.notes.toMutableList().apply { clear() } }
        located.forEach { item ->
            val globalTick = item.note.tick
            val owner = if (globalTick < starts[item.measure]) item.measure else
                starts.indices.lastOrNull { starts[it] <= globalTick }?.coerceAtMost(source.lastIndex) ?: item.measure
            result[owner] += item.note.copy(tick = globalTick - starts[owner])
        }
        return source.mapIndexed { i, measure -> measure.copy(notes = result[i].sortedBy { it.tick }) }
    }

    private fun realizeTempoAndSwing(source: List<Measure>, warnings: MutableSet<String>, budget: ResourceBudget): List<Measure> {
        if (source.isEmpty()) return source
        val starts = IntArray(source.size)
        for (i in 1 until source.size) starts[i] = checkedAddTick(starts[i - 1], source[i - 1].length, "聲部")
        var requestBpm = 120.0
        val requestedTempos = source.flatMapIndexed { i, measure ->
            measure.tempoRequests.map { it.copy(tick = starts[i] + it.tick) }
        }.sortedBy { it.tick }.mapNotNull { request ->
            val bpm = request.absolute ?: request.ratio?.let { requestBpm * it }
            bpm?.takeIf { it.isFinite() && it > 0.0 }?.let { Tempo(request.tick, it).also { requestBpm = it.bpm } }
        }
        val explicit = (source.flatMapIndexed { i, measure ->
            measure.tempos.map { it.copy(tick = starts[i] + it.tick) }
        } + requestedTempos).sortedBy { it.tick }
        val hints = source.flatMapIndexed { i, measure ->
            measure.tempoHints.map { starts[i] + it.tick }
        }.sorted()
        val rampStep = PPQ / 4
        val explicitTimeline = java.util.TreeMap<Int, Tempo>().apply {
            explicit.forEach { putIfAbsent(it.tick, it) }
        }
        val ramps = hints.mapNotNull { at ->
            explicitTimeline.higherEntry(at)?.value?.let { anchor -> at to anchor }
        }
        val generatedTempoCount = ramps.sumOf { (at, anchor) ->
            (anchor.tick.toLong() - at + rampStep - 1L) / rampStep
        }
        budget.reserveSource(generatedTempoCount, "漸快／漸慢 tempo ramp")
        val additions = mutableListOf<Tempo>()
        val tempoTimeline = java.util.TreeMap<Int, Double>().apply {
            explicit.forEach { putIfAbsent(it.tick, it.bpm) }
            putIfAbsent(0, 120.0)
        }
        hints.forEach { at ->
            val before = tempoTimeline.floorEntry(at)?.value ?: 120.0
            val anchor = explicitTimeline.higherEntry(at)?.value
            if (anchor == null) warnings += "漸快／漸慢記號後沒有明確 tempo anchor，未猜測終值"
            else {
                var tick = at
                while (tick < anchor.tick) {
                    val fraction = (tick - at).toDouble() / (anchor.tick - at)
                    val bpm = before + (anchor.bpm - before) * fraction
                    additions += Tempo(tick, bpm)
                    tempoTimeline.putIfAbsent(tick, bpm)
                    tick += rampStep
                }
            }
        }
        val requestedSwings = source.flatMapIndexed { i, measure -> measure.swings.map { it.copy(tick = starts[i] + it.tick) } }.sortedBy { it.tick }
        val swings = mutableListOf<SwingEvent>()
        requestedSwings.forEach { requested ->
            val active = swings.lastOrNull()
            val pair = (active?.unit ?: requested.unit) * 2
            val anchor = active?.tick ?: 0
            val relative = (requested.tick - anchor).coerceAtLeast(0)
            val effective = anchor + if (relative % pair == 0) relative else (relative / pair + 1) * pair
            swings += requested.copy(tick = effective)
        }
        fun mapped(global: Int): Int {
            val swing = swings.lastOrNull { it.tick <= global } ?: return global
            if (swing.straight || swing.first <= 0 || swing.second <= 0) return global
            val pair = swing.unit * 2
            val relative = global - swing.tick
            val pairStart = Math.floorDiv(relative, pair) * pair
            val within = relative - pairStart
            val firstLength = (pair.toDouble() * swing.first / (swing.first + swing.second)).roundToInt()
            val shifted = if (within <= swing.unit) within.toDouble() * firstLength / swing.unit
                else firstLength + (within - swing.unit).toDouble() * (pair - firstLength) / swing.unit
            return swing.tick + pairStart + shifted.roundToInt()
        }
        return source.mapIndexed { i, measure ->
            val start = starts[i]
            measure.copy(
                notes = measure.notes.map { note ->
                    val mappedStart = mapped(start + note.tick)
                    val mappedEnd = mapped(start + note.tick + note.duration)
                    note.copy(tick = mappedStart - start, duration = (mappedEnd - mappedStart).coerceAtLeast(1))
                },
                controls = measure.controls.map { control ->
                    val mappedStart = mapped(start + control.tick)
                    val mappedEnd = mapped(start + control.endTick)
                    control.copy(tick = mappedStart - start, endTick = mappedEnd - start)
                },
                tempos = (explicit.filter { it.tick in start..(start + measure.length) }.map { it.copy(tick = it.tick - start) } +
                    additions.filter { it.tick in start..(start + measure.length) }
                    .map { it.copy(tick = it.tick - start) }).sortedBy { it.tick })
        }
    }

    private fun endingNumbers(value: String): Set<Int> = buildSet {
        value.split(',').forEach { token ->
            val fields = token.trim().split('-')
            if (fields.size !in 1..2) return@forEach
            val numbers = fields.map { it.trim().toLongOrNull() }
            if (numbers.any { it == null }) return@forEach
            val low = minOf(numbers.first()!!, numbers.last()!!)
            val high = maxOf(numbers.first()!!, numbers.last()!!)
            require(low >= 1 && high <= MAX_ENDING_NUMBER) {
                "反覆結尾編號必須介於 1 到 $MAX_ENDING_NUMBER，拒絕過大的結尾範圍"
            }
            for (number in low..high) add(number.toInt())
        }
    }

    private fun parseNavigation(sound: Element?, words: String, tick: Int): List<Navigation> {
        val result = mutableListOf<Navigation>()
        fun add(attribute: String, kind: NavigationKind) {
            if (sound?.hasAttribute(attribute) == true) {
                val value = sound.getAttribute(attribute)
                if (value.isNotBlank() && value.lowercase() !in setOf("no", "false")) result += Navigation(tick, kind, value)
            }
        }
        add("dacapo", NavigationKind.DA_CAPO)
        add("dalsegno", NavigationKind.DAL_SEGNO)
        add("segno", NavigationKind.SEGNO)
        add("tocoda", NavigationKind.TO_CODA)
        add("coda", NavigationKind.CODA)
        add("fine", NavigationKind.FINE)
        if (result.isEmpty()) {
            val normalized = words.lowercase().replace(" ", "")
            when {
                Regex("d\\.?c\\.?").containsMatchIn(normalized) -> result += Navigation(tick, NavigationKind.DA_CAPO)
                Regex("d\\.?s\\.?").containsMatchIn(normalized) -> result += Navigation(tick, NavigationKind.DAL_SEGNO)
                "tocoda" in normalized -> result += Navigation(tick, NavigationKind.TO_CODA)
                normalized == "coda" -> result += Navigation(tick, NavigationKind.CODA)
                normalized == "segno" -> result += Navigation(tick, NavigationKind.SEGNO)
                normalized == "fine" -> result += Navigation(tick, NavigationKind.FINE)
            }
        }
        return result
    }

    private fun realizeWedges(source: List<Measure>, warnings: MutableSet<String>, budget: ResourceBudget): List<Measure> {
        if (source.none { it.wedges.isNotEmpty() }) return source
        val starts = IntArray(source.size)
        for (i in 1 until source.size) starts[i] = checkedAddTick(starts[i - 1], source[i - 1].length, "聲部")
        data class GlobalDynamic(val tick: Int, val event: DynamicEvent)
        data class GlobalWedge(val tick: Int, val mark: WedgeMark)
        val dynamics = source.flatMapIndexed { i, measure -> measure.dynamics.map { GlobalDynamic(starts[i] + it.tick, it) } }
            .sortedBy { it.tick }
        val wedges = source.flatMapIndexed { i, measure -> measure.wedges.map { GlobalWedge(starts[i] + it.tick, it) } }
            .sortedBy { it.tick }
        val notes = source.map { it.notes.toMutableList() }
        val controls = source.map { it.controls.toMutableList() }
        fun applies(event: DynamicEvent, staff: Int, voice: String) =
            (event.staff == 0 || event.staff == staff) && (event.voice.isBlank() || event.voice == voice)
        fun valueAt(tick: Int, staff: Int, voice: String) = dynamics.lastOrNull {
            it.tick <= tick && applies(it.event, staff, voice)
        }?.event?.value ?: 88
        fun apply(start: GlobalWedge, stopTick: Int) {
            if (stopTick <= start.tick) return
            val mark = start.mark
            val startValue = if (mark.niente && mark.type == "crescendo") 0 else valueAt(start.tick, mark.staff, mark.voice)
            val explicit = dynamics.firstOrNull {
                it.tick in (start.tick + 1)..stopTick && applies(it.event, mark.staff, mark.voice)
            }?.event?.value
            val targetValue = when {
                mark.niente && mark.type == "diminuendo" -> 0
                explicit != null -> explicit
                mark.type == "crescendo" -> (startValue + 18).coerceAtMost(127)
                else -> (startValue - 18).coerceAtLeast(1)
            }
            source.indices.forEach { measureIndex ->
                val measureStart = starts[measureIndex]
                val measureEnd = measureStart + source[measureIndex].length
                val overlapStart = maxOf(start.tick, measureStart)
                val overlapEnd = minOf(stopTick, measureEnd)
                if (overlapEnd < overlapStart) return@forEach
                fun interpolated(at: Int): Int {
                    val fraction = (at - start.tick).toDouble() / (stopTick - start.tick)
                    return (startValue + (targetValue - startValue) * fraction).roundToInt().coerceIn(0, 127)
                }
                if (mark.staff == 0 && mark.voice.isBlank()) {
                    budget.reserveSource(1, "漸強漸弱控制")
                    controls[measureIndex] += LocalControl(overlapStart - measureStart, overlapEnd - measureStart,
                        11, interpolated(overlapStart), interpolated(overlapEnd))
                } else {
                    warnings += "指定 staff／voice 的漸強漸弱以逐音力度近似播放"
                }
                notes[measureIndex].indices.forEach { noteIndex ->
                    val note = notes[measureIndex][noteIndex]
                    val globalTick = measureStart + note.tick
                    if (globalTick in overlapStart..overlapEnd &&
                        (mark.staff == 0 || note.staff == mark.staff) && (mark.voice.isBlank() || note.voice == mark.voice)) {
                        val marks = if (globalTick < stopTick) note.marks + "context:${mark.type}" else note.marks
                        notes[measureIndex][noteIndex] = if (mark.staff == 0 && mark.voice.isBlank()) note.copy(marks = marks)
                        else {
                            val baseline = valueAt(globalTick, note.staff, note.voice)
                            val accent = (note.velocity and 255) - baseline
                            note.copy(velocity = (interpolated(globalTick) + accent).coerceIn(1, 127) +
                                (note.velocity and 127.inv()), marks = marks)
                        }
                    }
                }
            }
        }
        val active = mutableMapOf<WedgeKey, GlobalWedge>()
        wedges.forEach { wedge ->
            val key = WedgeKey(wedge.mark.number, wedge.mark.staff, wedge.mark.voice)
            when (wedge.mark.type) {
                "crescendo", "diminuendo" -> active[key] = wedge
                "stop" -> active.remove(key)?.let { apply(it, wedge.tick) }
                "continue" -> Unit
            }
        }
        val scoreEnd = source.fold(0) { total, measure -> checkedAddTick(total, measure.length, "聲部") }
        active.values.forEach { apply(it, scoreEnd) }
        if (active.isNotEmpty()) warnings += "未結束的漸強漸弱已延伸至樂譜結尾"
        return source.mapIndexed { index, measure -> measure.copy(notes = notes[index], controls = controls[index]) }
    }

    private fun synchronizeTiming(source: List<List<Measure>>): List<List<Measure>> {
        val insertions = (0 until (source.maxOfOrNull { it.size } ?: 0)).associateWith { measureIndex ->
            source.mapNotNull { it.getOrNull(measureIndex) }.flatMap { it.insertions }
                .groupBy { it.tick }.map { (tick, values) -> TimingInsertion(tick, values.maxOf { it.duration }) }
                .sortedBy { it.tick }
        }
        return source.map { measures -> measures.mapIndexed { measureIndex, measure ->
            val timing = insertions[measureIndex].orEmpty()
            fun mapped(tick: Int) = checkedTick(tick.toLong() +
                timing.filter { it.tick <= tick }.sumOf { it.duration.toLong() }, "時間延長")
            fun mappedNote(tick: Int) = if (tick < 0) tick else mapped(tick)
            measure.copy(
                length = mapped(measure.length),
                notes = measure.notes.map { note ->
                    val end = checkedTick(note.tick.toLong() + note.duration, "音符")
                    note.copy(tick = mappedNote(note.tick), duration = mapped(end) - mappedNote(note.tick))
                },
                tempos = measure.tempos.map { it.copy(tick = mapped(it.tick)) },
                navigation = measure.navigation.map { it.copy(tick = mapped(it.tick)) },
                dynamics = measure.dynamics.map { it.copy(tick = mapped(it.tick)) },
                wedges = measure.wedges.map { it.copy(tick = mapped(it.tick)) },
                controls = measure.controls.map { it.copy(tick = mapped(it.tick), endTick = mapped(it.endTick)) },
                swings = measure.swings.map { it.copy(tick = mapped(it.tick)) },
                tempoHints = measure.tempoHints.map { it.copy(tick = mapped(it.tick)) },
                tempoRequests = measure.tempoRequests.map { it.copy(tick = mapped(it.tick)) },
                insertions = emptyList())
        } }
    }

    fun parse(bytes: ByteArray): Score {
        // MusicXML exports often contain an external DOCTYPE. Strip declarations only;
        // the XML parser still rejects internal entities and never loads external resources.
        val xml = normalizedXml(bytes).toString(Charsets.UTF_8)
        val clean = xml.replace(Regex("<!DOCTYPE[^\\[>]*>", RegexOption.IGNORE_CASE), "")
        val root = document(clean.toByteArray())
        require(root.tagName == "score-partwise") { "目前支援 score-partwise MusicXML，請以此格式重新匯出" }
        val warnings = linkedSetOf<String>()
        val definitions = root.child("part-list")?.children("score-part").orEmpty()
        val declaredChannels = definitions.flatMap { it.children("midi-instrument") }
            .mapNotNull { it.int("midi-channel").takeIf { value -> value in 1..16 }?.minus(1) }.toSet()
        val fallbackChannels = ((0..15).filter { it != 9 && it !in declaredChannels } +
            (0..15).filter { it != 9 && it in declaredChannels }).iterator()
        val parts = definitions.mapIndexed { partIndex, p ->
            val midiDefinitions = p.children("midi-instrument")
            val names = "${p.text("part-name")} ${p.children("score-instrument").joinToString(" ") { it.text("instrument-name") }}".lowercase()
            val inferred = listOf("violin" to 40, "小提琴" to 40, "viola" to 41, "中提琴" to 41,
                "cello" to 42, "大提琴" to 42, "contrabass" to 43, "flute" to 73, "長笛" to 73,
                "clarinet" to 71, "單簧管" to 71, "oboe" to 68, "雙簧管" to 68, "bassoon" to 70,
                "trumpet" to 56, "小號" to 56, "trombone" to 57, "長號" to 57, "horn" to 60,
                "sax" to 65, "guitar" to 24, "吉他" to 24, "harp" to 46, "豎琴" to 46,
                "organ" to 19, "管風琴" to 19, "bass" to 32).firstOrNull { names.contains(it.first) }?.second ?: 0
            val needsFallback = midiDefinitions.isEmpty() || midiDefinitions.any {
                it.int("midi-channel") !in 1..16 && it.int("midi-unpitched") <= 0
            }
            val deterministicChannel = if (partIndex >= 9) partIndex + 1 else partIndex
            val fallbackChannel = if (needsFallback && fallbackChannels.hasNext()) fallbackChannels.next()
                else midiDefinitions.firstNotNullOfOrNull {
                    it.int("midi-channel").takeIf { value -> value in 1..16 && value != 10 }?.minus(1)
                } ?: deterministicChannel
            val instruments = midiDefinitions.map { definition ->
                val percussion = definition.int("midi-channel") == 10 || definition.int("midi-unpitched") > 0
                val declaredChannel = definition.int("midi-channel").takeIf { it in 1..16 }?.minus(1)
                InstrumentSpec(definition.getAttribute("id"),
                    (definition.int("midi-program", inferred + 1) - 1).coerceIn(0, 127),
                    (definition.int("midi-bank", 1) - 1).coerceIn(0, 16383),
                    percussion, declaredChannel ?: if (percussion) 9 else fallbackChannel)
            }
            val primary = instruments.firstOrNull() ?: InstrumentSpec("", inferred, 0, false, fallbackChannel)
            Part(p.getAttribute("id"), p.text("part-name").ifBlank { "樂器" }, primary.program,
                primary.percussion, primary.bank, instruments, primary.channel)
        }
        require(parts.isNotEmpty() && parts.size <= 15) { "需有 1–15 個樂器聲部" }
        val budget = ResourceBudget()
        val rawByPart = parts.mapIndexed { partIndex, part ->
            val source = root.children("part").firstOrNull { it.getAttribute("id") == part.id }
                ?: error("找不到聲部 ${part.name}")
            var divisions = 1
            var transpose = 0
            var fifths = 0
            var nominal = PPQ * 4
            var dynamicOrder = 0
            val activeDynamics = mutableMapOf<Pair<Int, String>, DynamicEvent>()
            var partSourceTick = 0
            var activeEndings = emptySet<Int>()
            val percussionMap = definitions[partIndex].children("midi-instrument").associate {
                it.getAttribute("id") to (it.int("midi-unpitched", 61) - 1)
            }
            val pendingTremolos = mutableMapOf<Triple<String, Int, String>, PendingTremolo>()
            val priorGroups = mutableMapOf<Pair<String, Int>, PriorGroup>()
            val activeWavy = mutableSetOf<WavyKey>()
            val pendingGrace = mutableMapOf<Pair<String, Int>, MutableList<GraceNote>>()
            val parsedMeasures = source.children("measure").map { m ->
                var exactCursor = 0.0
                var cursor = 0
                var lastStartExact = 0.0
                var end = 0
                val notes = mutableListOf<Note>()
                val tempos = mutableListOf<Tempo>()
                val navigation = mutableListOf<Navigation>()
                val tempoRequests = mutableListOf<TempoRequest>()
                val dynamicEvents = mutableListOf<DynamicEvent>()
                val wedgeMarks = mutableListOf<WedgeMark>()
                val localControls = mutableListOf<LocalControl>()
                fun addControl(control: LocalControl) {
                    budget.reserveSource(1, "控制事件")
                    localControls += control
                }
                val timingInsertions = mutableListOf<TimingInsertion>()
                val swingEvents = mutableListOf<SwingEvent>()
                val tempoHints = mutableListOf<TempoHint>()
                val startingDynamics = activeDynamics.values.toList()
                var forward = false
                var repeat = 0
                var endingStops = false
                // Ending labels apply to the whole measure even when on its right barline.
                m.children("barline").forEach { b -> b.child("ending")?.let { ending ->
                    if (ending.getAttribute("type") == "start") {
                        activeEndings = endingNumbers(ending.getAttribute("number"))
                    } else endingStops = true
                } }
                val endings = activeEndings
                m.children().forEach { e -> when (e.tagName) {
                    "attributes" -> {
                        divisions = e.int("divisions", divisions).coerceAtLeast(1)
                        e.child("transpose")?.let { transpose = it.int("chromatic") + it.int("octave-change") * 12 }
                        e.child("key")?.let { fifths = it.int("fifths", fifths).coerceIn(-7, 7) }
                        e.child("time")?.let {
                            val beats = it.text("beats").split("+").sumOf { b -> b.toIntOrNull() ?: 0 }
                            nominal = (PPQ * 4.0 * beats.coerceAtLeast(1) / it.int("beat-type", 4).coerceAtLeast(1)).roundToInt()
                        }
                    }
                    "backup" -> {
                        exactCursor = (exactCursor - scaledTicks(durationUnits(e, "backup"), divisions, "backup")).coerceAtLeast(0.0)
                        cursor = exactCursor.roundToInt()
                    }
                    "forward" -> {
                        exactCursor = checkedExactTick(exactCursor +
                            scaledTicks(durationUnits(e, "forward"), divisions, "forward"), "forward")
                        cursor = exactCursor.roundToInt(); end = maxOf(end, cursor)
                    }
                    "direction" -> {
                        val offset = scaledOffset(e, divisions)
                        val directionTick = checkedExactTick(maxOf(0.0, exactCursor + offset), "控制事件").roundToInt()
                        val sound = e.child("sound")
                        val soundTempo = sound?.getAttribute("tempo")?.toDoubleOrNull()
                        val metronome = e.children("direction-type").flatMap { it.children("metronome") }.firstOrNull()
                        val metronomeTempo = metronome?.let(::metronomeTempo)
                        if (soundTempo != null) {
                            budget.reserveSource(1, "速度事件")
                            tempoRequests += TempoRequest(directionTick, absolute = soundTempo)
                        } else if (metronomeTempo != null) {
                            budget.reserveSource(1, "速度事件")
                            tempoRequests += TempoRequest(directionTick, metronomeTempo.first, metronomeTempo.second)
                        }
                        sound?.getAttribute("dynamics")?.toDoubleOrNull()?.let {
                            dynamicEvents += DynamicEvent(directionTick, (it * 0.9).toInt().coerceIn(1, 127),
                                e.int("staff"), e.text("voice"), "level", dynamicOrder++)
                        }
                        e.children("direction-type").flatMap { it.children("dynamics") }.flatMap { it.children() }
                            .firstOrNull()?.tagName?.let { code ->
                            dynamicVelocity(code)?.let { value -> dynamicEvents += DynamicEvent(directionTick, value,
                                e.int("staff"), e.text("voice"), code, dynamicOrder++) }
                        }
                        e.children("direction-type").flatMap { it.children("wedge") }.forEach { wedge ->
                            wedgeMarks += WedgeMark(directionTick, wedge.getAttribute("type"),
                                wedge.getAttribute("number").ifBlank { "1" }, e.int("staff"), e.text("voice"),
                                wedge.getAttribute("niente") == "yes")
                        }
                        e.children("direction-type").flatMap { it.children("pedal") }.forEach { pedal ->
                            val controller = when (pedal.getAttribute("pedal-type").ifBlank { pedal.getAttribute("type-name") }.lowercase()) {
                                "sostenuto" -> 66
                                "soft", "una-corda" -> 67
                                else -> 64
                            }
                            val level = pedal.getAttribute("value").takeIf { it.isNotBlank() }?.let(::pedalElementValue) ?: 127
                            when (pedal.getAttribute("type")) {
                                "start", "resume" -> addControl(LocalControl(directionTick, directionTick, controller, level))
                                "stop", "discontinue" -> addControl(LocalControl(directionTick, directionTick, controller, 0))
                                "change" -> {
                                    addControl(LocalControl(directionTick, directionTick, controller, 0))
                                    addControl(LocalControl(directionTick, directionTick, controller, level))
                                }
                                "continue" -> Unit
                            }
                        }
                        listOf("damper-pedal" to 64, "sostenuto-pedal" to 66, "soft-pedal" to 67).forEach { (attribute, controller) ->
                            sound?.getAttribute(attribute)?.takeIf { it.isNotBlank() }?.let { pedal ->
                                addControl(LocalControl(directionTick, directionTick, controller, soundPedalValue(pedal)))
                            }
                        }
                        val words = e.children("direction-type").flatMap { it.children("words") }.joinToString(" ") { it.textContent }
                        if (Regex("(?i)\\b(rit\\.?|ritardando|accel\\.?|accelerando)\\b").containsMatchIn(words)) {
                            budget.reserveSource(1, "速度提示")
                            tempoHints += TempoHint(directionTick)
                        }
                        val swing = sound?.child("swing") ?: e.children("direction-type").firstNotNullOfOrNull { it.child("swing") }
                        swing?.let {
                            val straight = it.child("straight") != null
                            val first = it.int("first", if (straight) 1 else 2)
                            val second = it.int("second", 1)
                            val unit = ((beatUnit(it.text("swing-type").ifBlank { "eighth" }) ?: .5) * PPQ).roundToInt().coerceAtLeast(1)
                            swingEvents += SwingEvent(directionTick, first, second, unit, straight)
                        }
                        navigation += parseNavigation(sound, words, directionTick.coerceIn(0, maxOf(end, nominal)))
                    }
                    "note" -> {
                        val requestedInstrumentId = e.child("instrument")?.getAttribute("id").orEmpty()
                        val instrumentSpec = part.instruments.firstOrNull { it.id == requestedInstrumentId }
                            ?: part.instruments.firstOrNull() ?: InstrumentSpec(requestedInstrumentId, part.program, part.bank,
                                part.percussion, part.channel)
                        val instrumentId = requestedInstrumentId.ifBlank { instrumentSpec.id }
                        fun pitchOf(note: Element): Int {
                            val pitchElement = note.child("pitch")
                            return if (pitchElement != null) {
                                val step = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)[pitchElement.text("step")] ?: 0
                                ((pitchElement.int("octave", 4) + 1) * 12 + step + pitchElement.int("alter") + transpose).coerceIn(0, 127)
                            } else (percussionMap[note.child("instrument")?.getAttribute("id")] ?: percussionMap.values.firstOrNull() ?: 60).coerceIn(0, 127)
                        }
                        e.child("grace")?.let { grace ->
                            if (e.child("rest") == null) {
                                budget.reserveSource(1, "裝飾音")
                                val graceVoice = e.text("voice").ifBlank { "1" }
                                val graceStaff = e.int("staff", 1)
                                pendingGrace.getOrPut(graceVoice to graceStaff) { mutableListOf() } += GraceNote(pitchOf(e), grace.getAttribute("slash") == "yes",
                                    grace.getAttribute("steal-time-following").toDoubleOrNull(),
                                    grace.getAttribute("steal-time-previous").toDoubleOrNull(),
                                    grace.getAttribute("make-time").toDoubleOrNull(), graceVoice,
                                    graceStaff, instrumentId, e.child("chord") != null, instrumentSpec.program,
                                    instrumentSpec.bank, instrumentSpec.channel, instrumentSpec.percussion)
                            }
                            return@forEach
                        }
                        val exactDuration = scaledTicks(durationUnits(e, "音符"), divisions, "音符")
                        val chord = e.child("chord") != null
                        val exactStart = if (chord) lastStartExact else exactCursor
                        val start = exactStart.roundToInt()
                        val exactNoteEnd = checkedExactTick(exactStart + exactDuration, "音符")
                        val duration = (exactNoteEnd.roundToInt() - start).coerceAtLeast(0)
                        if (!chord) {
                            lastStartExact = exactStart
                            exactCursor = exactNoteEnd; cursor = exactCursor.roundToInt()
                        }
                        end = maxOf(end, checkedAddTick(start, duration, "音符"))
                        if (e.child("rest") == null && duration > 0) {
                            val p = e.child("pitch")
                            val pitch = pitchOf(e)
                            val ties = e.children("tie").map { it.getAttribute("type") }
                            val notations = e.child("notations")
                            val semanticMarks = buildSet {
                                notations?.children("arpeggiate")?.forEach { mark -> add("arp:${mark.getAttribute("number").ifBlank { "1" }}:${mark.getAttribute("direction").ifBlank { "up" }}") }
                                notations?.children("non-arpeggiate")?.forEach { mark -> add("noarp:${mark.getAttribute("number").ifBlank { "1" }}") }
                                notations?.children("slur")?.forEach { mark -> add("slur:${mark.getAttribute("type")}:${mark.getAttribute("number").ifBlank { "1" }}") }
                                listOf("glissando", "slide").forEach { tag -> notations?.children(tag)?.forEach { mark ->
                                    add("gliss:${mark.getAttribute("type")}:${mark.getAttribute("number").ifBlank { "1" }}:$tag:${mark.textContent.trim()}")
                                } }
                                val articulation = notations?.child("articulations")
                                listOf("staccato", "staccatissimo", "tenuto", "accent", "strong-accent",
                                    "breath-mark", "caesura").filter { articulation?.child(it) != null }
                                    .forEach { add("context:$it") }
                                if (notations?.child("fermata") != null) add("context:fermata")
                            }
                            val articulations = notations?.child("articulations")
                            val accent = when {
                                articulations?.child("strong-accent") != null -> 20
                                articulations?.child("accent") != null -> 12
                                else -> 0
                            }
                            var soundingDuration = duration
                            if (ties.isEmpty()) soundingDuration = when {
                                articulations?.child("staccatissimo") != null -> maxOf(1, duration / 4)
                                articulations?.child("staccato") != null -> maxOf(1, duration / 2)
                                articulations?.child("tenuto") != null -> maxOf(1, duration * 95 / 100)
                                else -> duration
                            }
                            if (notations?.child("fermata") != null) {
                                // A fermata takes precedence over a detached articulation.
                                soundingDuration = duration
                                timingInsertions += TimingInsertion(checkedAddTick(start, duration, "延長記號"),
                                    maxOf(1, duration / 2))
                            }
                            var soundingStart = start
                            val noteVoice = e.text("voice").ifBlank { "1" }
                            val noteStaff = e.int("staff", 1)
                            val matchingGrace = if (chord) null else pendingGrace[noteVoice to noteStaff]
                            if (!matchingGrace.isNullOrEmpty()) {
                                val groups = mutableListOf<MutableList<GraceNote>>()
                                matchingGrace.forEach { grace ->
                                    if (grace.chord && groups.isNotEmpty()) groups.last() += grace else groups += mutableListOf(grace)
                                }
                                val madeTimeExact = matchingGrace.mapNotNull { it.makeTime }.maxOrNull()
                                    ?.let { it * PPQ / divisions }
                                val madeTime = madeTimeExact?.let { (exactStart + it).roundToInt() - start }
                                val previousPercent = matchingGrace.mapNotNull { it.stealPrevious }.maxOrNull()
                                val followingPercent = matchingGrace.mapNotNull { it.stealFollowing }.maxOrNull()
                                val previousGroup = priorGroups[noteVoice to noteStaff]?.takeIf { it.end == partSourceTick + start }
                                val previousNotes = previousGroup?.notes.orEmpty()
                                val previousDuration = previousGroup?.duration ?: 0
                                val requestedTotal = when {
                                    madeTime != null -> madeTime
                                    previousPercent != null && previousDuration > 0 ->
                                        (previousDuration * previousPercent / 100.0).roundToInt()
                                    else -> (duration * (followingPercent?.div(100.0)
                                        ?: if (matchingGrace.any { it.slash }) 0.125 else 0.25)).roundToInt()
                                }
                                val maximum = when {
                                    madeTime != null -> maxOf(groups.size, madeTime)
                                    previousPercent != null && previousDuration > 0 -> maxOf(groups.size, previousDuration / 2)
                                    else -> maxOf(groups.size, duration / 2)
                                }
                                val graceTotal = requestedTotal.coerceIn(groups.size, maximum)
                                val each = maxOf(1, graceTotal / groups.size)
                                val stealsPrevious = madeTime == null && previousPercent != null && previousDuration > 0
                                val previousMeasure = stealsPrevious && previousGroup!!.measureStart != partSourceTick
                                val graceBase = when {
                                    previousMeasure -> -graceTotal
                                    stealsPrevious -> previousGroup!!.end - previousGroup.measureStart - graceTotal
                                    else -> start
                                }
                                val graceOwner = if (stealsPrevious && !previousMeasure) previousGroup!!.owner else notes
                                groups.forEachIndexed { groupIndex, group ->
                                    val graceStart = graceBase + groupIndex * each
                                    val graceDuration = if (groupIndex == groups.lastIndex) graceTotal - groupIndex * each else each
                                    group.forEach { grace ->
                                            graceOwner += Note(graceStart, maxOf(1, graceDuration), grace.pitch, 88, partIndex,
                                             grace.voice, grace.staff, grace.instrument, grace.program, grace.bank, grace.channel,
                                             grace.percussion, if (stealsPrevious) setOf("gracePrevious") else emptySet())
                                    }
                                }
                                when {
                                    madeTime != null -> {
                                        soundingStart += graceTotal
                                        if (soundingDuration == duration) {
                                            soundingDuration = ((exactStart + madeTimeExact!! + exactDuration).roundToInt() -
                                                soundingStart).coerceAtLeast(1)
                                        }
                                        exactCursor += madeTimeExact!!
                                        lastStartExact += madeTimeExact!!
                                        cursor = exactCursor.roundToInt()
                                        end = maxOf(end, soundingStart + soundingDuration)
                                    }
                                    stealsPrevious -> Unit // Shorten the performed predecessor after repeat/navigation expansion.
                                    else -> {
                                        soundingStart += graceTotal
                                        soundingDuration = maxOf(1, soundingDuration - graceTotal)
                                    }
                                }
                                pendingGrace.remove(noteVoice to noteStaff)
                            }
                            val baseVelocity = (88 + accent).coerceIn(1, 127)
                            val ornaments = notations?.child("ornaments")
                            val wavyLines = ornaments?.children("wavy-line").orEmpty()
                            fun wavyKey(mark: Element) = WavyKey(mark.getAttribute("number").ifBlank { "1" },
                                noteVoice, noteStaff, instrumentId)
                            wavyLines.filter { it.getAttribute("type") in setOf("start", "continue") }.forEach {
                                activeWavy += wavyKey(it)
                            }
                            val wavyActive = activeWavy.any {
                                it.voice == noteVoice && it.staff == noteStaff && it.instrument == instrumentId
                            } || wavyLines.any { it.getAttribute("type") == "stop" }
                            val tremolo = ornaments?.child("tremolo")
                            val tremoloType = tremolo?.getAttribute("type").orEmpty().ifBlank { "single" }
                            val tremoloMarks = tremolo?.textContent?.trim()?.toIntOrNull()?.coerceIn(1, 8) ?: 1
                            val identity = Triple(noteVoice, noteStaff, instrumentId)
                            val ornament = ornaments?.children()?.firstOrNull {
                                it.tagName in setOf("trill-mark", "mordent", "inverted-mordent", "turn", "inverted-turn",
                                    "delayed-turn", "delayed-inverted-turn")
                            }?.tagName ?: if (wavyActive) "trill-mark" else null
                            val performanceMarks = semanticMarks + listOfNotNull(
                                ornament?.let { "context:$it" }, tremolo?.let { "context:tremolo" })
                            fun ornamentPitch(offset: Int): Int {
                                if (p == null) return pitch
                                val steps = listOf("C", "D", "E", "F", "G", "A", "B")
                                val semitones = listOf(0, 2, 4, 5, 7, 9, 11)
                                val sourceIndex = steps.indexOf(p.text("step")).coerceAtLeast(0)
                                val targetIndex = sourceIndex + offset
                                val normalized = (targetIndex % 7 + 7) % 7
                                val targetStep = steps[normalized]
                                val octave = p.int("octave", 4) + Math.floorDiv(targetIndex, 7)
                                val marks = ornaments?.children("accidental-mark").orEmpty()
                                val mark = marks.firstOrNull { it.getAttribute("placement") == if (offset > 0) "above" else "below" }
                                    ?: marks.singleOrNull()?.takeIf { it.getAttribute("placement").isBlank() }
                                val markedAlter = mark?.textContent?.trim()?.lowercase()?.let {
                                    mapOf("flat-flat" to -2, "flat" to -1, "natural" to 0, "sharp" to 1,
                                        "double-sharp" to 2, "sharp-sharp" to 2)[it]
                                }
                                val alter = markedAlter ?: when {
                                    fifths > 0 && targetStep in listOf("F", "C", "G", "D", "A", "E", "B").take(fifths) -> 1
                                    fifths < 0 && targetStep in listOf("B", "E", "A", "D", "G", "C", "F").take(-fifths) -> -1
                                    else -> 0
                                }
                                return ((octave + 1) * 12 + semitones[normalized] + alter + transpose).coerceIn(0, 127)
                            }
                            fun addPattern(pattern: List<Int>) {
                                val played = pattern.take(soundingDuration)
                                budget.reserveSource(played.size.toLong(), "裝飾奏法")
                                val unit = minOf(PPQ / 4, maxOf(1, soundingDuration / played.size))
                                var offset = 0
                                played.forEachIndexed { index, ornamentPitch ->
                                    val length = if (index == played.lastIndex) soundingDuration - offset else unit
                                    notes += Note(soundingStart + offset, length, ornamentPitch, baseVelocity,
                                        partIndex, identity.first, identity.second, identity.third,
                                        instrumentSpec.program, instrumentSpec.bank, instrumentSpec.channel,
                                        instrumentSpec.percussion, performanceMarks)
                                    offset += length
                                }
                            }
                            val performanceStart = notes.size
                            if (ornament != null && p != null && ties.isEmpty()) {
                                val upper = ornamentPitch(1)
                                val lower = ornamentPitch(-1)
                                val pattern = when (ornament) {
                                    "mordent" -> listOf(pitch, lower, pitch)
                                    "inverted-mordent" -> listOf(pitch, upper, pitch)
                                    "turn" -> listOf(upper, pitch, lower, pitch)
                                    "inverted-turn" -> listOf(lower, pitch, upper, pitch)
                                    "delayed-turn" -> listOf(pitch, pitch, upper, pitch, lower, pitch)
                                    "delayed-inverted-turn" -> listOf(pitch, pitch, lower, pitch, upper, pitch)
                                    else -> listOf(pitch, upper)
                                }
                                if (ornament == "trill-mark") {
                                    val unit = minOf(PPQ / 4, maxOf(1, soundingDuration / 2))
                                    budget.reserveSource((soundingDuration.toLong() + unit - 1L) / unit, "顫音")
                                    var offset = 0
                                    var index = 0
                                    while (offset < soundingDuration) {
                                        val length = minOf(unit, soundingDuration - offset)
                                        notes += Note(soundingStart + offset, length, pattern[index % pattern.size],
                                            baseVelocity, partIndex, identity.first, identity.second, identity.third,
                                            instrumentSpec.program, instrumentSpec.bank, instrumentSpec.channel,
                                            instrumentSpec.percussion, performanceMarks)
                                        offset += length
                                        index++
                                    }
                                } else addPattern(pattern)
                            } else if (tremolo != null && tremoloType == "single" && ties.isEmpty()) {
                                val unit = maxOf(1, PPQ / (1 shl tremoloMarks))
                                budget.reserveSource((soundingDuration.toLong() + unit - 1L) / unit, "震音")
                                var offset = 0
                                while (offset < soundingDuration) {
                                    val length = minOf(unit, soundingDuration - offset)
                                    notes += Note(soundingStart + offset, length, pitch, baseVelocity, partIndex,
                                        identity.first, identity.second, identity.third, instrumentSpec.program,
                                        instrumentSpec.bank, instrumentSpec.channel, instrumentSpec.percussion,
                                        performanceMarks)
                                    offset += length
                                }
                            } else if (tremolo != null && tremoloType == "stop" && ties.isEmpty()) {
                                val pending = pendingTremolos.remove(identity)
                                if (pending != null && pending.note.velocity and (TIE_START or TIE_STOP) == 0) {
                                    val pendingNote = pending.owner.firstOrNull { it.tick == pending.note.tick &&
                                        it.pitch == pending.note.pitch &&
                                        it.part == pending.note.part && it.voice == pending.note.voice &&
                                        it.staff == pending.note.staff && it.instrument == pending.note.instrument } ?: pending.note
                                    pending.owner.remove(pendingNote)
                                    val unit = maxOf(1, PPQ / (1 shl maxOf(pending.marks, tremoloMarks)))
                                    budget.releaseSource(1)
                                    budget.reserveSource((pendingNote.duration.toLong() + unit - 1L) / unit +
                                        (soundingDuration.toLong() + unit - 1L) / unit, "雙音震音")
                                    var offset = 0
                                    var second = false
                                    while (offset < pendingNote.duration) {
                                        val length = minOf(unit, pendingNote.duration - offset)
                                        pending.owner += Note(pendingNote.tick + offset, length,
                                            if (second) pitch else pendingNote.pitch, (pendingNote.velocity and 255) +
                                                (if (offset + length == pendingNote.duration) pendingNote.velocity and 127.inv() else 0),
                                            partIndex, identity.first, identity.second, identity.third,
                                            pendingNote.program, pendingNote.bank, pendingNote.channel,
                                            pendingNote.percussion, pendingNote.marks)
                                        offset += length
                                        second = !second
                                    }
                                    offset = 0
                                    while (offset < soundingDuration) {
                                        val length = minOf(unit, soundingDuration - offset)
                                        notes += Note(soundingStart + offset, length, if (second) pitch else pendingNote.pitch,
                                            baseVelocity, partIndex, identity.first, identity.second, identity.third,
                                            instrumentSpec.program, instrumentSpec.bank, instrumentSpec.channel,
                                            instrumentSpec.percussion, performanceMarks)
                                        offset += length
                                        second = !second
                                    }
                                } else {
                                    budget.reserveSource(1, "音符")
                                    notes += Note(soundingStart, soundingDuration, pitch, baseVelocity, partIndex,
                                        identity.first, identity.second, identity.third, instrumentSpec.program,
                                        instrumentSpec.bank, instrumentSpec.channel, instrumentSpec.percussion, performanceMarks)
                                }
                            } else {
                                // Tie flags are stored temporarily in the velocity high bits and merged after repeats.
                                budget.reserveSource(1, "音符")
                                val plain = Note(soundingStart, soundingDuration, pitch, baseVelocity +
                                    (if ("start" in ties) TIE_START else 0) + (if ("stop" in ties) TIE_STOP else 0), partIndex,
                                    identity.first, identity.second, identity.third, instrumentSpec.program,
                                    instrumentSpec.bank, instrumentSpec.channel, instrumentSpec.percussion, performanceMarks)
                                notes += plain
                                if (tremolo != null && tremoloType == "start" && ties.isEmpty()) {
                                    pendingTremolos[identity] = PendingTremolo(notes, plain, tremoloMarks)
                                }
                            }
                            val breathing = when {
                                articulations?.child("caesura") != null -> CAESURA + (minOf(duration / 2, PPQ / 2) shl 12)
                                articulations?.child("breath-mark") != null -> BREATH + (minOf(duration / 4, PPQ / 4) shl 12)
                                else -> 0
                            }
                            if (breathing != 0 && notes.size > performanceStart) {
                                if (ties.isEmpty()) {
                                    val cutAt = soundingStart + soundingDuration - (breathing ushr 12)
                                    for (noteIndex in notes.lastIndex downTo performanceStart) {
                                        val performance = notes[noteIndex]
                                        if (performance.tick >= cutAt) notes.removeAt(noteIndex)
                                        else if (performance.tick + performance.duration > cutAt) {
                                            notes[noteIndex] = performance.copy(duration = cutAt - performance.tick)
                                        }
                                    }
                                } else {
                                    val last = notes.lastIndex
                                    notes[last] = notes[last].copy(velocity = notes[last].velocity + breathing)
                                }
                            }
                            val created = notes.subList(performanceStart, notes.size).filter {
                                it.voice == identity.first && it.staff == identity.second
                            }.toList()
                            val priorKey = identity.first to identity.second
                            val globalStart = partSourceTick + start
                            priorGroups[priorKey] = if (chord && priorGroups[priorKey]?.start == globalStart) {
                                val previous = priorGroups.getValue(priorKey)
                                previous.copy(notes = previous.notes + created)
                            } else PriorGroup(notes, created, globalStart, globalStart + duration, duration, partSourceTick)
                            wavyLines.filter { it.getAttribute("type") == "stop" }.forEach {
                                activeWavy -= wavyKey(it)
                            }
                            ornaments?.children()?.filter { it.tagName !in setOf("trill-mark", "wavy-line", "accidental-mark",
                                "mordent", "inverted-mordent", "turn", "inverted-turn", "delayed-turn",
                                "delayed-inverted-turn", "tremolo") }?.takeIf { it.isNotEmpty() }
                                ?.let { warnings += "部分裝飾奏法目前以原音播放" }
                        }
                    }
                    "barline" -> e.child("repeat")?.let {
                        if (it.getAttribute("direction") == "forward") forward = true
                        else repeat = (it.getAttribute("times").toIntOrNull() ?: 2).coerceIn(2, 8)
                    }
                } }
                val orderedDynamics = dynamicEvents.sortedWith(compareBy<DynamicEvent> { it.tick }.thenBy { it.order })
                notes.indices.forEach { noteIndex ->
                    val note = notes[noteIndex]
                    val current = orderedDynamics.asSequence().filter { event -> event.tick <= note.tick }
                        .map { it.copy(tick = partSourceTick + it.tick) }
                    val effective = (startingDynamics.asSequence() + current).filter { event ->
                        event.tick <= partSourceTick + note.tick &&
                            (event.staff == 0 || event.staff == note.staff) &&
                            (event.voice.isBlank() || event.voice == note.voice)
                    }.maxWithOrNull(compareBy<DynamicEvent> { it.tick }.thenBy { it.order })
                    val flags = note.velocity and 127.inv()
                    val accentDelta = (note.velocity and 255) - 88
                    val base = effective?.value ?: 88
                    notes[noteIndex] = note.copy(velocity = (base + accentDelta).coerceIn(1, 127) + flags,
                        marks = effective?.let { note.marks + "context:dynamic:${it.code}:$base" } ?: note.marks)
                }
                orderedDynamics.forEach { event ->
                    activeDynamics[event.staff to event.voice] = event.copy(tick = partSourceTick + event.tick)
                }
                if (endingStops) activeEndings = emptySet()
                Measure(m.getAttribute("number"), if (m.getAttribute("implicit") == "yes") end else maxOf(end, nominal),
                    notes, tempos, forward, repeat, endings, navigation.distinct(), dynamicEvents, wedgeMarks, localControls,
                    timingInsertions, swingEvents, tempoHints, tempoRequests).also {
                    partSourceTick = checkedAddTick(partSourceTick, it.length, "聲部")
                }
            }
            if (pendingGrace.isNotEmpty()) warnings += "樂譜結尾的裝飾音沒有可連接的主音"
            if (pendingTremolos.isNotEmpty()) warnings += "雙音震音沒有對應的 stop"
            parsedMeasures
        }
        val byPart = synchronizeTiming(rawByPart).map {
            realizeTempoAndSwing(realizeWedges(realizePartSemantics(it, warnings, budget), warnings, budget), warnings, budget)
        }
        val firstPart = byPart.first()
        require(firstPart.isNotEmpty()) { "樂譜沒有小節" }
        val master = firstPart.indices.map { index ->
            val measures = byPart.mapNotNull { it.getOrNull(index) }
            if (measures.map { it.forward }.distinct().size > 1 || measures.map { it.repeat }.distinct().size > 1 ||
                measures.map { it.endings }.distinct().size > 1) {
                warnings += "小節 ${firstPart[index].number} 的聲部反覆標記不一致，已合併處理"
            }
            firstPart[index].copy(length = measures.maxOf { it.length }, forward = measures.any { it.forward },
                repeat = measures.maxOf { it.repeat }, endings = measures.flatMap { it.endings }.toSet(),
                navigation = measures.flatMap { it.navigation }.distinct())
        }
        val openRegions = mutableListOf<Int>()
        val regions = mutableListOf<RepeatRegion>()
        master.forEachIndexed { i, measure ->
            if (measure.forward) openRegions += i
            if (measure.repeat > 0) {
                val start = if (openRegions.isEmpty()) 0 else openRegions.removeAt(openRegions.lastIndex)
                regions += RepeatRegion(start, i, measure.repeat)
            }
        }
        if (openRegions.isNotEmpty()) warnings += "反覆開始記號沒有對應的結束記號"
        val regionsByStart = regions.groupBy { it.start }.mapValues { (_, value) -> value.sortedByDescending { it.end } }
        val segnos = master.flatMapIndexed { i, measure -> measure.navigation.filter { it.kind == NavigationKind.SEGNO }.map { i to it } }
        val codas = master.flatMapIndexed { i, measure -> measure.navigation.filter { it.kind == NavigationKind.CODA }.map { i to it } }
        fun target(markers: List<Pair<Int, Navigation>>, name: String, after: Int = -1): Pair<Int, Navigation>? {
            val named = name.takeUnless { it.isBlank() || it.equals("yes", true) }
            return markers.firstOrNull { (i, marker) -> i > after && (named == null || marker.target == named) }
                ?: markers.firstOrNull { (_, marker) -> named == null || marker.target == named }
        }
        val plan = mutableListOf<Triple<Int, Int, Int>>()
        val frames = mutableListOf<RepeatFrame>()
        val visited = mutableSetOf<String>()
        var index = 0
        var completedPass: Int? = null
        var navigationActive = false
        var jumpTaken = false
        var codaTaken = false
        var stoppedAtFine = false
        var suppressRepeats = false
        var pendingSourceStart = 0
        val finalEnding = IntArray(master.size) { 1 }
        regions.sortedBy { it.start }.forEachIndexed { regionIndex, region ->
            val nextStart = regions.filterIndexed { index, candidate -> index != regionIndex && candidate.start > region.end }
                .minOfOrNull { it.start } ?: master.size
            var first = region.end
            // A repeat may start on the preceding region's final ending; that shared measure belongs to the old group.
            while (first - 1 > region.start && master[first - 1].endings.isNotEmpty()) first--
            var last = region.end
            while (last + 1 < master.size && last + 1 <= nextStart && master[last + 1].endings.isNotEmpty()) last++
            val final = (first..last).flatMap { master[it].endings }.maxOrNull() ?: 1
            for (i in first..last) finalEnding[i] = final
        }
        while (index in master.indices) {
            require(plan.size < 10000) { "反覆與跳轉展開超過 10000 個片段" }
            val state = "$index:$pendingSourceStart|${frames.joinToString { "${it.region.start}:${it.region.end}:${it.pass}" }}|$navigationActive|$jumpTaken|$codaTaken|$suppressRepeats"
            if (!visited.add(state)) {
                warnings += "偵測到無限反覆或跳轉，已在小節 ${master[index].number} 停止"
                break
            }
            if (!suppressRepeats) regionsByStart[index].orEmpty().forEach { region ->
                if (frames.none { it.region == region }) { frames += RepeatFrame(region); completedPass = null }
            }
            val measure = master[index]
            val pass = if (suppressRepeats) finalEnding[index] else completedPass ?: frames.lastOrNull()?.pass ?: 1
            val repeatAtEnd = if (suppressRepeats) null else frames.lastOrNull { it.region.end == index }
            if (measure.endings.isNotEmpty() && pass !in measure.endings) {
                if (repeatAtEnd != null && repeatAtEnd.pass >= repeatAtEnd.region.times) {
                    frames.remove(repeatAtEnd); completedPass = repeatAtEnd.pass
                }
                index++; pendingSourceStart = 0; continue
            }
            val finalRepeatPass = repeatAtEnd == null || repeatAtEnd.pass >= repeatAtEnd.region.times
            val sourceStart = pendingSourceStart.coerceIn(0, measure.length)
            val action = measure.navigation.filter { event -> event.tick >= sourceStart && when (event.kind) {
                NavigationKind.FINE -> navigationActive
                NavigationKind.DA_CAPO, NavigationKind.DAL_SEGNO -> !jumpTaken && finalRepeatPass
                NavigationKind.TO_CODA -> navigationActive && !codaTaken
                else -> false
            } }.minWithOrNull(compareBy<Navigation> { it.tick }.thenBy { it.kind.ordinal })
            val sourceEnd = (action?.tick ?: measure.length).coerceIn(0, measure.length)
            if (sourceEnd > sourceStart) plan += Triple(index, sourceStart, sourceEnd)
            pendingSourceStart = 0
            if (action != null) when (action.kind) {
                NavigationKind.FINE -> { stoppedAtFine = true; break }
                NavigationKind.DA_CAPO -> {
                    jumpTaken = true; navigationActive = true; suppressRepeats = true
                    frames.clear(); completedPass = null; index = 0; continue
                }
                NavigationKind.DAL_SEGNO -> {
                    val destination = target(segnos, action.target)
                    if (destination == null) warnings += "D.S. 找不到 Segno，已忽略跳轉" else {
                        jumpTaken = true; navigationActive = true; suppressRepeats = true
                        frames.clear(); completedPass = null; index = destination.first
                        pendingSourceStart = destination.second.tick; continue
                    }
                }
                NavigationKind.TO_CODA -> {
                    val destination = target(codas, action.target, index)
                    if (destination == null) warnings += "To Coda 找不到 Coda，已忽略跳轉" else {
                        codaTaken = true; frames.clear(); completedPass = null; index = destination.first
                        pendingSourceStart = destination.second.tick; continue
                    }
                }
                else -> Unit
            }
            if (measure.endings.isNotEmpty() && completedPass != null) completedPass = null
            val endingFrame = repeatAtEnd
            if (endingFrame != null) {
                if (endingFrame.pass < endingFrame.region.times) {
                    endingFrame.pass++; completedPass = endingFrame.pass; index = endingFrame.region.start; continue
                }
                frames.remove(endingFrame); completedPass = endingFrame.pass
            }
            index++
        }
        if (jumpTaken && !stoppedAtFine && master.any { m -> m.navigation.any { it.kind == NavigationKind.FINE } }) {
            warnings += "跳轉後未到達 Fine，已播放至樂譜結尾"
        }
        var tick = 0
        val bars = mutableListOf<Bar>()
        data class RawNote(var note: Note, val continuity: Int)
        val raw = mutableListOf<RawNote>()
        val controls = mutableListOf<Control>()
        budget.reserveExpanded(1, "速度事件")
        val tempos = mutableListOf(Tempo(0, 120.0))
        var sourceBpm = 120.0
        val startingTempos = master.indices.map { i ->
            val starting = sourceBpm
            byPart.flatMap { it.getOrNull(i)?.tempos.orEmpty() }.sortedBy { it.tick }.lastOrNull()?.let { sourceBpm = it.bpm }
            starting
        }
        val occurrences = mutableMapOf<Int, Int>()
        var previousMeasure = -1
        var previousEnd = -1
        var continuity = 0
        plan.forEach { (i, sourceStart, sourceEnd) ->
            val length = sourceEnd - sourceStart
            val occurrence = (occurrences[i] ?: 0) + 1
            occurrences[i] = occurrence
            val skippedEndingsOnly = i > previousMeasure + 1 && previousMeasure >= 0 &&
                (previousMeasure + 1 until i).all { master[it].endings.isNotEmpty() }
            val discontinuous = (i != previousMeasure + 1 && !skippedEndingsOnly) || sourceStart != 0 ||
                previousMeasure !in master.indices || previousEnd != master[previousMeasure].length
            if (discontinuous) continuity++
            budget.reserveExpanded(1, "小節")
            bars += Bar(master[i].number, tick, length, i, occurrence)
            if (discontinuous) {
                val localTempo = byPart.flatMap { it.getOrNull(i)?.tempos.orEmpty() }.filter { it.tick <= sourceStart }
                    .maxByOrNull { it.tick }?.bpm ?: startingTempos[i]
                budget.reserveExpanded(1, "速度事件")
                tempos += Tempo(tick, localTempo)
                parts.indices.forEach { part ->
                    budget.reserveExpanded(4, "控制事件")
                    listOf(64, 66, 67).forEach { controls += Control(tick, tick, part, it, 0) }
                    controls += Control(tick, tick, part, 11, 127)
                }
            }
            byPart.forEachIndexed { p, measures -> measures.getOrNull(i)?.let { measure ->
                val selectedNotes = measure.notes.filter {
                    it.tick < sourceEnd && (it.tick.toLong() + it.duration.toLong() > sourceStart ||
                        it.tick < 0 && sourceStart == 0)
                }
                budget.reserveExpanded(selectedNotes.size.toLong(), "音符")
                raw += selectedNotes.map { note ->
                    val leadingGrace = note.tick < 0 && sourceStart == 0
                    val clippedStart = if (leadingGrace) note.tick else maxOf(note.tick, sourceStart)
                    val clippedEnd = if (leadingGrace) checkedAddTick(note.tick, note.duration, "裝飾音") else
                        minOf(checkedAddTick(note.tick, note.duration, "音符"),
                            checkedAddTick(sourceEnd, if ("carry" in note.marks) minOf(PPQ / 20, 24) else 0, "音符"))
                    RawNote(note.copy(tick = checkedTick(tick.toLong() + clippedStart - sourceStart, "展開後音符"),
                        duration = clippedEnd - clippedStart), continuity)
                }
                if (p == 0 || master[i].tempos.isEmpty()) {
                    val selectedTempos = measure.tempos.filter { it.tick in sourceStart..sourceEnd }
                    budget.reserveExpanded(selectedTempos.size.toLong(), "速度事件")
                    tempos += selectedTempos.map { it.copy(tick = tick + it.tick - sourceStart) }
                }
                measure.controls.forEach { control ->
                    if (control.endTick == control.tick) {
                        if (control.tick in sourceStart..sourceEnd) {
                            budget.reserveExpanded(1, "控制事件")
                            controls += Control(tick + (control.tick - sourceStart).coerceAtLeast(0),
                                tick + (control.tick - sourceStart).coerceAtLeast(0), p, control.controller, control.from, control.to)
                        }
                    } else {
                        val clippedStart = maxOf(sourceStart, control.tick)
                        val clippedEnd = minOf(sourceEnd, control.endTick)
                        if (clippedEnd > clippedStart) {
                            fun value(at: Int): Int {
                                val fraction = (at - control.tick).toDouble() / (control.endTick - control.tick)
                                return (control.from + (control.to - control.from) * fraction).roundToInt().coerceIn(0, 127)
                            }
                            budget.reserveExpanded(1, "控制事件")
                            controls += Control(tick + clippedStart - sourceStart, tick + clippedEnd - sourceStart, p,
                                control.controller, value(clippedStart), value(clippedEnd))
                        }
                    }
                }
            } }
            tick = checkedAddTick(tick, length, "展開後樂譜"); previousMeasure = i; previousEnd = sourceEnd
        }
        raw.filter { "gracePrevious" in it.note.marks }.forEach { grace ->
            val boundary = grace.note.tick + grace.note.duration
            for (index in raw.lastIndex downTo 0) {
                val candidate = raw[index]
                if (candidate === grace || "gracePrevious" in candidate.note.marks ||
                    candidate.note.part != grace.note.part || candidate.note.voice != grace.note.voice ||
                    candidate.note.staff != grace.note.staff || candidate.note.tick >= boundary ||
                    candidate.note.tick + candidate.note.duration <= grace.note.tick) continue
                if (candidate.note.tick >= grace.note.tick) raw.removeAt(index)
                else candidate.note = candidate.note.copy(duration = grace.note.tick - candidate.note.tick)
            }
        }
        val contextFragments = raw.flatMap { item -> item.note.marks.filter { it.startsWith("context:") }.map { mark ->
            val fields = mark.split(':')
            val code = if (fields.getOrNull(1) == "dynamic") "dynamic:${fields.getOrElse(2) { "level" }}"
                else fields.getOrElse(1) { "" }
            PerformanceContext(item.note.tick, item.note.tick + item.note.duration, item.note.part, code,
                fields.getOrNull(3)?.toIntOrNull(), item.note.voice, item.note.staff)
        } }.filter { it.code.isNotBlank() }
        val contexts = mutableListOf<PerformanceContext>()
        contextFragments.groupBy { listOf(it.part, it.code, it.value, it.voice, it.staff) }.values.forEach { fragments ->
            fragments.sortedBy { it.tick }.forEach { fragment ->
                val previous = contexts.lastOrNull()?.takeIf {
                    it.part == fragment.part && it.code == fragment.code && it.value == fragment.value &&
                        it.voice == fragment.voice && it.staff == fragment.staff && fragment.tick <= it.endTick
                }
                if (previous == null) contexts += fragment
                else contexts[contexts.lastIndex] = previous.copy(endTick = maxOf(previous.endTick, fragment.endTick))
            }
        }
        contexts.sortBy { it.tick }
        val merged = mutableListOf<Note>()
        val pending = mutableMapOf<TieKey, Int>()
        fun released(note: Note, flags: Int, terminalDuration: Int): Note {
            val encodedGap = flags ushr 12
            val gap = when {
                encodedGap > 0 -> encodedGap
                flags and CAESURA != 0 -> minOf(terminalDuration / 2, PPQ / 2)
                flags and BREATH != 0 -> minOf(terminalDuration / 4, PPQ / 4)
                else -> 0
            }
            return note.copy(duration = maxOf(1, note.duration - gap), velocity = note.velocity and 255)
        }
        raw.sortedBy { it.note.tick }.forEach { rawNote ->
            val n = rawNote.note
            val key = TieKey(rawNote.continuity, n.part, n.pitch, n.voice, n.staff, n.instrument)
            val priorIndex = pending[key]
            val prior = priorIndex?.let { merged[it] }
            val stop = n.velocity and TIE_STOP != 0
            val start = n.velocity and TIE_START != 0
            if (stop && prior != null && prior.tick + prior.duration == n.tick) {
                val joined = prior.copy(duration = checkedAddTick(prior.duration, n.duration, "連結音"))
                merged[priorIndex] = if (start) joined else released(joined, n.velocity, n.duration)
                if (!start) pending.remove(key)
            } else {
                merged += if (start) n.copy(velocity = n.velocity and 255) else released(n, n.velocity, n.duration)
                if (start) pending[key] = merged.lastIndex else pending.remove(key)
            }
        }
        budget.reserveExpanded(parts.size.toLong() * 3, "控制事件")
        parts.indices.forEach { part -> listOf(64, 66, 67).forEach { controls += Control(tick, tick, part, it, 0) } }
        val distinctTempos = tempos.distinctBy { it.tick to it.bpm }.sortedBy { it.tick }
        val playbackMillis = expandedDurationMillis(tick, distinctTempos)
        require(playbackMillis.isFinite() && playbackMillis <= MAX_PLAYBACK_MILLIS + PLAYBACK_MILLIS_TOLERANCE) {
            "展開後樂譜超過 30 分鐘，請縮短樂譜或減少反覆"
        }
        return Score(root.text("movement-title").ifBlank { root.child("work")?.text("work-title").orEmpty() }.ifBlank { "未命名樂譜" },
            root.child("identification")?.children("creator")?.firstOrNull { it.getAttribute("type") == "composer" }?.textContent.orEmpty(),
            parts, merged.map { it.copy(marks = emptySet()) }, distinctTempos, bars, tick, warnings.toList(),
            controls.distinct().sortedBy { it.tick }, contexts)
    }
}
