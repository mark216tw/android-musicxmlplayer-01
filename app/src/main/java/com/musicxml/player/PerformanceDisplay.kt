package com.musicxml.player

object InstrumentNames {
    private val gm = listOf(
        "平台鋼琴", "明亮鋼琴", "電平台鋼琴", "酒吧鋼琴", "電鋼琴 1", "電鋼琴 2", "大鍵琴", "古鋼琴",
        "鋼片琴", "鐘琴", "音樂盒", "顫音琴", "馬林巴琴", "木琴", "管鐘", "揚琴",
        "拉桿風琴", "打擊風琴", "搖滾風琴", "教堂管風琴", "簧風琴", "手風琴", "口琴", "探戈手風琴",
        "尼龍弦吉他", "鋼弦吉他", "爵士電吉他", "清音電吉他", "悶音電吉他", "破音吉他", "失真吉他", "吉他泛音",
        "原音貝斯", "指彈貝斯", "撥片貝斯", "無格貝斯", "拍弦貝斯 1", "拍弦貝斯 2", "合成貝斯 1", "合成貝斯 2",
        "小提琴", "中提琴", "大提琴", "低音提琴", "顫音弦樂", "撥奏弦樂", "豎琴", "定音鼓",
        "弦樂合奏 1", "弦樂合奏 2", "合成弦樂 1", "合成弦樂 2", "人聲啊", "人聲喔", "合成人聲", "管弦樂重擊",
        "小號", "長號", "低音號", "弱音小號", "法國號", "銅管合奏", "合成銅管 1", "合成銅管 2",
        "高音薩克斯風", "中音薩克斯風", "次中音薩克斯風", "上低音薩克斯風", "雙簧管", "英國管", "巴松管", "單簧管",
        "短笛", "長笛", "直笛", "排笛", "瓶笛", "尺八", "哨笛", "陶笛",
        "方波主奏", "鋸齒波主奏", "汽笛風琴主奏", "合成吹管主奏", "吉他主奏", "人聲主奏", "五度主奏", "貝斯主奏",
        "新世紀音墊", "溫暖音墊", "多重合成音墊", "合唱音墊", "弓弦音墊", "金屬音墊", "光環音墊", "掃掠音墊",
        "雨聲效果", "電影音效", "水晶音效", "氣氛音效", "明亮音效", "精靈音效", "回音音效", "科幻音效",
        "西塔琴", "斑鳩琴", "三味線", "箏", "卡林巴琴", "風笛", "民俗提琴", "山奈琴",
        "叮噹鈴", "阿哥哥鼓", "鋼鼓", "木魚", "太鼓", "旋律鼓", "合成鼓", "反向鈸",
        "吉他滑弦聲", "呼吸聲", "海浪聲", "鳥鳴聲", "電話鈴聲", "直升機聲", "掌聲", "槍聲"
    )

    val choices = listOf(0, 4, 19, 24, 25, 32, 40, 41, 42, 43, 46, 48, 56, 57, 58, 60, 65, 68,
        70, 71, 73, 74, 11, 13, 47).map { it to name(it) }

    fun name(program: Int, percussion: Boolean = false) = if (percussion) "鼓組"
        else gm.getOrNull(program) ?: "GM 音色 ${program + 1}"
}

object PerformanceDisplay {
    private val labels = mapOf(
        "crescendo" to "漸強", "diminuendo" to "漸弱", "glissando" to "滑音", "slide" to "滑奏",
        "trill-mark" to "顫音", "mordent" to "下波音", "inverted-mordent" to "上波音",
        "turn" to "迴音", "inverted-turn" to "倒迴音", "delayed-turn" to "延後迴音",
        "delayed-inverted-turn" to "延後倒迴音", "tremolo" to "震音", "arpeggio" to "琶音",
        "legato" to "圓滑奏", "staccato" to "斷奏", "staccatissimo" to "極短斷奏",
        "tenuto" to "保持音", "accent" to "重音", "strong-accent" to "強重音",
        "fermata" to "延長記號", "breath-mark" to "換氣", "caesura" to "短暫休止"
    )
    private val dynamics = mapOf(
        "pppp" to "極弱", "ppp" to "很弱", "pp" to "弱", "p" to "弱",
        "mp" to "中弱", "mf" to "中強", "f" to "強", "ff" to "很強",
        "fff" to "極強", "ffff" to "極強", "fp" to "強後弱", "sf" to "突強",
        "sfp" to "突強後弱", "sfpp" to "突強後很弱", "sfz" to "突強",
        "sffz" to "強烈突強", "fz" to "突強", "rf" to "再強", "rfz" to "再突強"
    )

    fun instrumentLabels(score: Score, tick: Int, programs: List<Int>, overrides: List<Boolean>): List<String> =
        instrumentLabels(score, tick, programs, overrides, score.timeline.activeNotes(tick))

    private fun instrumentLabels(score: Score, tick: Int, programs: List<Int>, overrides: List<Boolean>,
                                 activeNotes: List<Note>): List<String> = run {
        val active = activeNotes.groupBy { it.part }
        score.parts.indices.map { part ->
            if (overrides.getOrElse(part) { false }) {
                InstrumentNames.name(programs.getOrElse(part) { score.parts[part].program }, score.parts[part].percussion)
            } else {
                val relevant = active[part].orEmpty().ifEmpty { score.timeline.latestOnset(part, tick) }
                relevant.map { InstrumentNames.name(if (it.program >= 0) it.program else score.parts[part].program,
                    it.percussion || score.parts[part].percussion) }.distinct().ifEmpty {
                    listOf(InstrumentNames.name(score.parts[part].program, score.parts[part].percussion))
                }.joinToString("／")
            }
        }
    }

    fun context(score: Score, tick: Int, enabled: List<Boolean>, programs: List<Int>, overrides: List<Boolean>): String =
        context(score, tick, enabled, programs, overrides, score.timeline.activeNotes(tick))

    private fun context(score: Score, tick: Int, enabled: List<Boolean>, programs: List<Int>, overrides: List<Boolean>,
                        activeNotes: List<Note>): String {
        val result = mutableListOf<String>()
        score.parts.indices.forEach { part -> if (enabled.getOrElse(part) { true }) {
            score.timeline.dynamicContexts(part, tick, activeNotes).forEach { context ->
                val code = context.code.substringAfter(':')
                result += dynamics[code] ?: "力度 ${context.value ?: ""}".trim()
            }
        } }
        score.timeline.activeContexts(tick).filter {
            enabled.getOrElse(it.part) { true } && !it.code.startsWith("dynamic:")
        }.forEach { context ->
            labels[context.code]?.let(result::add)
        }
        score.timeline.activeControls(tick).filter { enabled.getOrElse(it.part) { true } && it.controller == 11 }
            .map { if (it.to >= it.from) "漸強" else "漸弱" }.forEach(result::add)
        listOf(64 to "延音踏板", 66 to "持音踏板", 67 to "弱音踏板").forEach { (controller, label) ->
            val levels = score.parts.indices.mapNotNull { part -> if (!enabled.getOrElse(part) { true }) null else
                score.timeline.latestControl(part, controller, tick)?.to }
            val level = levels.maxOrNull() ?: 0
            if (level > 0) result += if (level < 127) "$label ${maxOf(1, level * 100 / 127)}%" else label
        }
        activeNotes.filter { note -> enabled.getOrElse(note.part) { true } &&
            !overrides.getOrElse(note.part) { false } && (note.program != score.parts[note.part].program ||
                note.instrument.isNotBlank() && note.instrument != score.parts[note.part].instruments.firstOrNull()?.id) }
            .map { "音色：${InstrumentNames.name(it.program, it.percussion)}" }.distinct().forEach(result::add)
        return result.distinct().joinToString("・")
    }

    fun snapshot(score: Score, tick: Int, enabled: List<Boolean>, playing: Boolean,
                 programs: List<Int>, overrides: List<Boolean>): PerformanceSnapshot {
        val activeNotes = score.timeline.activeNotes(tick)
        return PerformanceSnapshot(
            tick,
            if (playing) activeNotes.asSequence().filter { enabled.getOrElse(it.part) { true } }.map { it.part }.toSet() else emptySet(),
            instrumentLabels(score, tick, programs, overrides, activeNotes),
            context(score, tick, enabled, programs, overrides, activeNotes)
        )
    }
}

data class PerformanceSnapshot(val tick: Int, val activeParts: Set<Int>,
                               val instrumentLabels: List<String>, val context: String)
