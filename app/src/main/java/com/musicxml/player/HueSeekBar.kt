package com.musicxml.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.SeekBar

class HueSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBar(context, attrs) {
    init {
        max = 359
        progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
            )
        ).apply { cornerRadius = resources.displayMetrics.density * 7 }
        splitTrack = false
    }
}
