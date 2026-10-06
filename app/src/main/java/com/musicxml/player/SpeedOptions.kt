package com.musicxml.player

object SpeedOptions {
    val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
    val labels = values.map { "${(it * 100).toInt()}%" }

    fun normalize(value: Float): Float = values.firstOrNull { value == it } ?: 1.0f

    fun indexOf(value: Float): Int = values.indices.first { values[it] == normalize(value) }
}
