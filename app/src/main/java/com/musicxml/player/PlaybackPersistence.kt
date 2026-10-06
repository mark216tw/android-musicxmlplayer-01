package com.musicxml.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackSessionSnapshot(
    val queueIds: List<String>,
    val playOrderIds: List<String>,
    val currentId: String,
    val positionMillis: Int,
    val shuffle: Boolean,
    val repeatMode: RepeatMode
)

class PlaybackPersistence(private val context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun speed(library: Library): Float {
        migrateGlobals(library)
        return SpeedOptions.normalize(prefs.getFloat("speed", 1f))
    }

    fun style(library: Library): PerformanceStyle {
        migrateGlobals(library)
        return PerformanceStyle.fromStored(prefs.getString("performanceStyle", null).orEmpty())
    }

    fun saveGlobals(speed: Float, style: PerformanceStyle) {
        prefs.edit()
            .putBoolean("globalsMigrated", true)
            .putFloat("speed", SpeedOptions.normalize(speed))
            .putString("performanceStyle", style.name)
            .apply()
    }

    private fun migrateGlobals(library: Library) {
        if (prefs.getBoolean("globalsMigrated", false)) return
        val recent = library.entries.maxByOrNull { maxOf(it.lastPlayed, it.imported) }
        prefs.edit()
            .putBoolean("globalsMigrated", true)
            .putFloat("speed", SpeedOptions.normalize(recent?.speed ?: 1f))
            .putString("performanceStyle", (recent?.performanceStyle ?: PerformanceStyle.ORIGINAL).name)
            .apply()
    }

    fun saveSession(snapshot: PlaybackSessionSnapshot?) {
        if (snapshot == null) {
            prefs.edit().remove("session").apply()
            return
        }
        val value = JSONObject().apply {
            put("queueIds", JSONArray(snapshot.queueIds))
            put("playOrderIds", JSONArray(snapshot.playOrderIds))
            put("currentId", snapshot.currentId)
            put("positionMillis", snapshot.positionMillis.coerceAtLeast(0))
            put("shuffle", snapshot.shuffle)
            put("repeatMode", snapshot.repeatMode.name)
        }
        prefs.edit().putString("session", value.toString()).apply()
    }

    fun loadSession(validIds: Set<String>): PlaybackSessionSnapshot? = runCatching {
        val value = JSONObject(prefs.getString("session", null) ?: return null)
        fun ids(key: String) = value.getJSONArray(key).let { array ->
            (0 until array.length()).map { array.getString(it) }.filter { it in validIds }.distinct()
        }
        val queue = ids("queueIds")
        if (queue.isEmpty()) return null
        val current = value.getString("currentId")
        if (current !in queue) return null
        val savedOrder = ids("playOrderIds")
        val order = (savedOrder + queue).distinct().filter { it in queue }
        PlaybackSessionSnapshot(
            queue,
            order,
            current,
            value.optInt("positionMillis", 0),
            value.optBoolean("shuffle", false),
            runCatching { RepeatMode.valueOf(value.optString("repeatMode")) }.getOrDefault(RepeatMode.OFF)
        )
    }.getOrNull()
}
