package com.musicxml.player

enum class PerformanceStyle(val label: String, val description: String) {
    ORIGINAL("原譜", "依照樂譜原始力度與時間演奏"),
    POP("流行", "節奏較明確，強拍較突出"),
    LYRICAL("抒情", "音符較連貫，力度較柔和"),
    NATURAL("自然", "加入輕微的時間與力度變化"),
    ROMANTIC("浪漫", "延長音符並增加力度起伏");

    companion object {
        val labels = entries.map { it.label }.toTypedArray()
        val options = entries.map { "${it.label}\n${it.description}" }.toTypedArray()
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

data class PerformanceNote(
    val source: Note,
    val startMillis: Int,
    val endMillis: Int
) {
    val part: Int get() = source.part
    val pitch: Int get() = source.pitch
    val velocity: Int get() = source.velocity
    val program: Int get() = source.program
    val bank: Int get() = source.bank
    val channel: Int get() = source.channel
    val percussion: Boolean get() = source.percussion
}

data class PerformanceControl(
    val source: Control,
    val startMillis: Int,
    val endMillis: Int
)

/** Immutable, rendered event data shared by realtime playback and WAV export. */
data class PerformancePlan internal constructor(
    val source: Score,
    val style: PerformanceStyle,
    val notes: List<PerformanceNote>,
    val controls: List<PerformanceControl>,
    val durationMillis: Int,
    private val canonicalMillis: List<Int>,
    private val renderedMillis: List<Int>
) {
    val renderedDurationMillis: Int get() = durationMillis

    fun performanceMillisAt(canonicalPosition: Int): Int {
        val canonicalEnd = source.millisAt(source.endTick).coerceAtLeast(0)
        if (canonicalPosition <= 0 || durationMillis <= 0) return 0
        if (canonicalPosition >= canonicalEnd) return durationMillis.coerceAtLeast(0)
        if (canonicalMillis.isEmpty() || renderedMillis.isEmpty())
            return canonicalPosition.coerceIn(0, durationMillis)
        val lastIndex = minOf(canonicalMillis.lastIndex, renderedMillis.lastIndex)
        val value = canonicalPosition.coerceIn(0, canonicalEnd)
        var low = 0
        var high = lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (canonicalMillis[mid] <= value) low = mid else high = mid - 1
        }
        if (low == lastIndex) return renderedMillis[low].coerceIn(0, durationMillis)
        val left = canonicalMillis[low]
        val right = canonicalMillis[low + 1]
        if (right <= left) return renderedMillis[low].coerceIn(0, durationMillis)
        val result = renderedMillis[low].toLong() + (value.toLong() - left.toLong()) *
            (renderedMillis[low + 1].toLong() - renderedMillis[low].toLong()) /
            (right.toLong() - left.toLong())
        return result.coerceIn(0L, durationMillis.toLong()).toInt()
    }

    fun canonicalMillisAt(performanceMillis: Int): Int {
        val canonicalEnd = source.millisAt(source.endTick).coerceAtLeast(0)
        if (performanceMillis <= 0 || canonicalEnd <= 0) return 0
        if (performanceMillis >= durationMillis) return canonicalEnd
        if (canonicalMillis.isEmpty() || renderedMillis.isEmpty())
            return performanceMillis.coerceIn(0, canonicalEnd)
        val lastIndex = minOf(canonicalMillis.lastIndex, renderedMillis.lastIndex)
        val value = performanceMillis.coerceIn(0, durationMillis)
        if (value > renderedMillis[lastIndex]) return canonicalEnd
        var low = 0
        var high = lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (renderedMillis[mid] <= value) low = mid else high = mid - 1
        }
        if (low == lastIndex) return canonicalMillis[low].coerceIn(0, canonicalEnd)
        val left = renderedMillis[low]
        val right = renderedMillis[low + 1]
        if (right <= left) return canonicalMillis[low].coerceIn(0, canonicalEnd)
        val result = canonicalMillis[low].toLong() + (value.toLong() - left.toLong()) *
            (canonicalMillis[low + 1].toLong() - canonicalMillis[low].toLong()) /
            (right.toLong() - left.toLong())
        return result.coerceIn(0L, canonicalEnd.toLong()).toInt()
    }
}
