package com.musicxml.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

enum class DisplayMode { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean) = this == DARK || (this == SYSTEM && systemDark)
}
data class ThemeColorPreset(val name: String, val hue: Int)

object Appearance {
    const val DEFAULT_HUE = 338
    val themePresets = listOf(
        ThemeColorPreset("元氣粉", DEFAULT_HUE),
        ThemeColorPreset("莓果紅", 350),
        ThemeColorPreset("珊瑚橘", 12),
        ThemeColorPreset("葡萄紫", 285),
        ThemeColorPreset("晴空藍", 210),
        ThemeColorPreset("薄荷綠", 155)
    )
    private fun preferences(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun mode(context: Context): DisplayMode = runCatching {
        DisplayMode.valueOf(preferences(context).getString("displayMode", "SYSTEM")!!)
    }.getOrDefault(DisplayMode.SYSTEM)
    fun setMode(context: Context, mode: DisplayMode) {
        preferences(context).edit().putString("displayMode", mode.name).apply()
    }
    fun dark(context: Context) = mode(context).isDark(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    fun hue(context: Context) = normalizeHue(preferences(context).getInt("themeHue", DEFAULT_HUE))
    fun setHue(context: Context, hue: Int) { preferences(context).edit().putInt("themeHue", normalizeHue(hue)).apply() }
    fun normalizeHue(hue: Int) = Math.floorMod(hue, 360)
    fun accent(hue: Int, dark: Boolean) = Color.HSVToColor(floatArrayOf(normalizeHue(hue).toFloat(), if (dark) 0.62f else 0.76f, if (dark) 1f else 0.82f))
}
data class UiColors(val dark: Boolean, val hue: Int = Appearance.DEFAULT_HUE) {
    val background = if (dark) 0xff0b0e13.toInt() else 0xfff4f5f7.toInt()
    val surface = if (dark) 0xff171c24.toInt() else 0xffffffff.toInt()
    val ink = if (dark) 0xfff2f5f7.toInt() else 0xff171c24.toInt()
    val muted = if (dark) 0xff99a5b3.toInt() else 0xff66717e.toInt()
    val grid = if (dark) 0xff202733.toInt() else 0xffeceff2.toInt()
    val line = if (dark) 0xff343e4d.toInt() else 0xffd5dbe1.toInt()
    val accent = Appearance.accent(hue, dark)
    val onAccent = if (dark) 0xff170b12.toInt() else Color.WHITE
}
object PartColors {
    private val light = intArrayOf(0xff159e96.toInt(), 0xffe97b61.toInt(), 0xff677ed0.toInt(), 0xffbb8c26.toInt(), 0xffb566a9.toInt())
    private val dark = intArrayOf(0xff54d8ce.toInt(), 0xffffa28b.toInt(), 0xff9dadff.toInt(), 0xffe4bf60.toInt(), 0xffe99bd8.toInt())
    fun color(index: Int, darkMode: Boolean): Int = (if (darkMode) dark else light).let { it[index % it.size] }
}
object VolumeOptions {
    const val DEFAULT = 100
    val values = intArrayOf(0, 25, 50, 75, 100, 125)
    val labels = arrayOf("0（無聲）", "25", "50", "75", "100（基準）", "125（最大）")

    fun normalize(value: Int) = values.firstOrNull { it == value } ?: DEFAULT
    fun indexOf(value: Int) = values.indices.first { values[it] == normalize(value) }
}
