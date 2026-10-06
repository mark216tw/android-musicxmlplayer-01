package com.musicxml.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

class PianoRoll(context: Context, val score: Score, dark: Boolean = Appearance.dark(context), private val seek: (Int) -> Unit) : View(context) {
    var tick = 0
        private set
    var enabled = score.parts.map { true }
        private set
    var activeParts = emptySet<Int>()
        private set
    var instrumentLabels = score.parts.map { InstrumentNames.name(it.program, it.percussion) }
        private set
    var performanceContext = ""
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = UiColors(dark)
    private var startTick = 0
    private var window = PPQ * 12
    private var gridTop = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touchMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val low = score.notes.minOfOrNull { it.pitch }?.minus(3)?.coerceAtLeast(0) ?: 48
    private val high = score.notes.maxOfOrNull { it.pitch }?.plus(3)?.coerceAtMost(127) ?: 72
    private val density get() = resources.displayMetrics.density

    init {
        setBackgroundColor(colors.surface)
        contentDescription = if (score.notes.isEmpty()) "音符卷軸，全曲休止" else "音符卷軸，顯示目前播放位置與音符"
        minimumHeight = dp(250).toInt()
    }

    fun updatePlayback(tick: Int, enabled: List<Boolean>, playing: Boolean, programs: List<Int>, overrides: List<Boolean>) {
        updatePlayback(PerformanceDisplay.snapshot(score, tick, enabled, playing, programs, overrides), enabled)
    }

    fun updatePlayback(snapshot: PerformanceSnapshot, enabled: List<Boolean>) {
        instrumentLabels = snapshot.instrumentLabels
        tick = snapshot.tick
        this.enabled = enabled
        activeParts = snapshot.activeParts
        performanceContext = snapshot.context
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = dp(250).toInt()
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(32)
        val available = width - left
        gridTop = dp(22)
        val rowHeight = (height - gridTop) / (high - low + 1)
        startTick = (tick - PPQ * 2).coerceAtLeast(0)
        window = PPQ * 12
        fun x(t: Int) = left + (t - startTick).toFloat() / window * available

        paint.textSize = dp(10); paint.textAlign = Paint.Align.LEFT; paint.isFakeBoldText = false; paint.alpha = 255
        for (pitch in low..high) {
            val y = gridTop + (high - pitch) * rowHeight
            paint.color = if (pitch % 12 in listOf(1, 3, 6, 8, 10)) colors.grid else colors.surface
            canvas.drawRect(left, y, width.toFloat(), y + rowHeight, paint)
            if (pitch % 12 == 0) {
                paint.color = colors.muted
                canvas.drawText("C${pitch / 12 - 1}", dp(2), y + rowHeight, paint)
            }
        }
        score.timeline.visibleBars(startTick, startTick + window).forEach {
            paint.color = colors.line; paint.strokeWidth = density
            canvas.drawLine(x(it.tick), gridTop, x(it.tick), height.toFloat(), paint)
            paint.color = colors.muted
            canvas.drawText(it.number, x(it.tick) + dp(3), dp(15), paint)
        }

        canvas.save(); canvas.clipRect(left, gridTop, width.toFloat(), height.toFloat())
        score.timeline.visibleNotes(startTick, startTick + window).forEach {
            val active = it.part in activeParts
            paint.color = if (!enabled.getOrElse(it.part) { true }) colors.muted else PartColors.color(it.part, colors.dark)
            paint.alpha = if (active) 255 else 155
            val y = gridTop + (high - it.pitch) * rowHeight
            canvas.drawRoundRect(x(it.tick), y + 1, x(it.tick + it.duration).coerceAtLeast(x(it.tick) + 3),
                y + rowHeight - 1, dp(3), dp(3), paint)
        }
        paint.alpha = 255; paint.color = colors.ink; paint.strokeWidth = dp(2)
        canvas.drawLine(x(tick), gridTop, x(tick), height.toFloat(), paint)
        canvas.restore()
        if (score.notes.isEmpty()) {
            paint.color = colors.ink; paint.textSize = dp(18); paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText("全曲休止", width / 2f, gridTop + (height - gridTop) / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX = event.x; touchY = event.y; touchMoved = false }
            MotionEvent.ACTION_MOVE -> if (hypot(event.x - touchX, event.y - touchY) > touchSlop) touchMoved = true
            MotionEvent.ACTION_UP -> {
                val left = dp(32)
                if (!touchMoved && event.y >= gridTop && event.x in left..width.toFloat()) {
                    seek((startTick + (event.x - left) / (width - left) * window).toInt().coerceIn(0, score.endTick))
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> touchMoved = true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(value: Int) = value * density
}
