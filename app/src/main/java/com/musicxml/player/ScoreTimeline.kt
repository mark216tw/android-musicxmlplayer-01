package com.musicxml.player

/** Immutable indexes for the playback and drawing queries made repeatedly for a score. */
class ScoreTimeline internal constructor(private val score: Score) {
    private data class Indexed<T>(val value: T, val order: Int, val start: Int, val end: Int)

    private class Intervals<T>(values: List<T>, start: (T) -> Int, end: (T) -> Int) {
        private class Node<T>(
            val center: Int,
            val crossingByStart: List<Indexed<T>>,
            val crossingByEnd: List<Indexed<T>>,
            val left: Node<T>?,
            val right: Node<T>?
        )

        private val root = build(values.mapIndexed { index, value -> Indexed(value, index, start(value), end(value)) })

        private fun build(items: List<Indexed<T>>): Node<T>? {
            if (items.isEmpty()) return null
            val center = items.map { it.start }.sorted()[items.size / 2]
            val left = items.filter { it.end <= center }
            val right = items.filter { it.start > center }
            val crossing = items.filter { it.end > center && it.start <= center }
            return Node(center, crossing.sortedBy { it.start }, crossing.sortedByDescending { it.end }, build(left), build(right))
        }

        fun at(tick: Int): List<T> = range(tick, tick + 1)

        fun range(start: Int, end: Int): List<T> {
            if (end <= start) return emptyList()
            val result = mutableListOf<Indexed<T>>()
            fun query(node: Node<T>?) {
                node ?: return
                when {
                    end <= node.center -> {
                        node.crossingByStart.takeWhile { it.start < end }.forEach(result::add)
                        query(node.left)
                    }
                    start > node.center -> {
                        node.crossingByEnd.takeWhile { it.end > start }.forEach(result::add)
                        query(node.right)
                    }
                    else -> {
                        result += node.crossingByStart
                        query(node.left); query(node.right)
                    }
                }
            }
            query(root)
            return result.filter { it.start < end && it.end > start }.sortedBy { it.order }.map { it.value }
        }
    }

    private val orderedBars = score.bars.sortedBy { it.tick }
    private val noteIntervals = Intervals(score.notes, Note::tick) { it.tick + it.duration }
    private val contextIntervals = Intervals(score.contexts, PerformanceContext::tick, PerformanceContext::endTick)
    private val controlIntervals = Intervals(score.controls.filter { it.endTick > it.tick }, Control::tick, Control::endTick)
    private val notesByPart = score.notes.groupBy { it.part }
    private val contextsByPart = score.contexts.groupBy { it.part }
    private val onsetsByPart = score.parts.indices.map { part ->
        notesByPart[part].orEmpty().groupBy { it.tick }.entries.sortedBy { it.key }.map { it.key to it.value }
    }
    private val dynamicsByPart = score.parts.indices.map { part ->
        contextsByPart[part].orEmpty().filter { it.code.startsWith("dynamic:") }.sortedBy { it.tick }
    }
    private val controlsByPartAndNumber = score.controls.groupBy { it.part to it.controller }.mapValues { (_, values) -> values.sortedBy { it.tick } }

    fun barAt(tick: Int): Bar? = orderedBars.lastAtOrBefore(tick) { it.tick }

    fun visibleBars(startTick: Int, endTick: Int): List<Bar> {
        if (endTick < startTick) return emptyList()
        val first = orderedBars.lowerBound(startTick) { it.tick }
        val last = orderedBars.upperBound(endTick) { it.tick }
        return orderedBars.subList(first, last)
    }

    fun activeNotes(tick: Int): List<Note> = noteIntervals.at(tick)
    fun visibleNotes(startTick: Int, endTick: Int): List<Note> = noteIntervals.range(startTick, endTick)
    fun activeContexts(tick: Int): List<PerformanceContext> = contextIntervals.at(tick)
    fun activeControls(tick: Int): List<Control> = controlIntervals.at(tick)

    fun latestOnset(part: Int, tick: Int): List<Note> {
        val onsets = onsetsByPart.getOrNull(part).orEmpty()
        val index = onsets.upperBound(tick) { it.first } - 1
        return if (index >= 0) onsets[index].second else emptyList()
    }

    fun dynamicContext(part: Int, tick: Int): PerformanceContext? =
        dynamicsByPart.getOrNull(part)?.lastAtOrBefore(tick) { it.tick }

    fun dynamicContexts(part: Int, tick: Int, notes: List<Note>): List<PerformanceContext> {
        val dynamics = dynamicsByPart.getOrNull(part).orEmpty()
        val relevant = notes.filter { it.part == part }
        if (relevant.isEmpty()) return listOfNotNull(dynamics.lastAtOrBefore(tick) { it.tick })
        return relevant.mapNotNull { note ->
            dynamics.lastOrNull { context -> context.tick <= tick &&
                (context.staff == 0 || context.staff == note.staff) &&
                (context.voice.isBlank() || context.voice == note.voice) }
        }.distinct()
    }

    fun latestControl(part: Int, controller: Int, tick: Int): Control? =
        controlsByPartAndNumber[part to controller]?.lastAtOrBefore(tick) { it.tick }

    private fun <T> List<T>.lowerBound(value: Int, key: (T) -> Int): Int {
        var low = 0; var high = size
        while (low < high) { val mid = (low + high) ushr 1; if (key(this[mid]) < value) low = mid + 1 else high = mid }
        return low
    }

    private fun <T> List<T>.upperBound(value: Int, key: (T) -> Int): Int {
        var low = 0; var high = size
        while (low < high) { val mid = (low + high) ushr 1; if (key(this[mid]) <= value) low = mid + 1 else high = mid }
        return low
    }

    private fun <T> List<T>.lastAtOrBefore(value: Int, key: (T) -> Int): T? =
        getOrNull(upperBound(value, key) - 1)
}
