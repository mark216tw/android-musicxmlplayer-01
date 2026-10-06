package com.musicxml.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LibraryEntry(val id: String, var name: String, val composer: String, val instruments: String,
                          val imported: Long, var lastPlayed: Long = 0, var speed: Float = 1f,
                          var performanceStyle: PerformanceStyle = PerformanceStyle.ORIGINAL,
                          var volumes: List<Int> = emptyList(), var programs: List<Int> = emptyList(),
                          var durationMillis: Int? = null, var programOverrides: List<Boolean> = emptyList())

data class Playlist(
    val id: String,
    var name: String,
    val created: Long,
    var updated: Long = created,
    val trackIds: MutableList<String> = mutableListOf()
)

class Library(private val context: Context) {
    private val directory = File(context.filesDir, "scores").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    val entries = mutableListOf<LibraryEntry>()
    val playlists = mutableListOf<Playlist>()
    init {
        val array = JSONArray(prefs.getString("entries", "[]"))
        var normalized = false
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            fun list(key: String): List<Int> = item.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            fun booleans(key: String): List<Boolean> = item.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getBoolean(it) } }.orEmpty()
            val savedSpeed = item.optDouble("speed", 1.0).toFloat()
            val speed = SpeedOptions.normalize(savedSpeed)
            normalized = normalized || savedSpeed != speed
            entries += LibraryEntry(item.getString("id"), item.getString("name"), item.optString("composer"),
                item.optString("instruments"), item.getLong("imported"), item.optLong("lastPlayed"),
                speed, PerformanceStyle.fromStored(item.optString("performanceStyle")), list("volumes"), list("programs"),
                if (item.has("durationMillis") && !item.isNull("durationMillis")) item.getInt("durationMillis") else null,
                booleans("programOverrides"))
        }
        val playlistArray = JSONArray(prefs.getString("playlists", "[]"))
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            val tracks = item.optJSONArray("trackIds")
            playlists += Playlist(
                item.getString("id"),
                item.getString("name"),
                item.optLong("created", System.currentTimeMillis()),
                item.optLong("updated", item.optLong("created", System.currentTimeMillis())),
                (0 until (tracks?.length() ?: 0)).map { tracks!!.getString(it) }.toMutableList()
            )
        }
        val validIds = entries.mapTo(hashSetOf()) { it.id }
        playlists.forEach { it.trackIds.retainAll(validIds) }
        if (normalized) save()
    }
    fun file(entry: LibraryEntry) = File(directory, "${entry.id}.musicxml")
    fun add(bytes: ByteArray, score: Score): LibraryEntry {
        val entry = LibraryEntry(UUID.randomUUID().toString(), score.title, score.composer,
            score.parts.joinToString("、") { it.name }, System.currentTimeMillis(),
            durationMillis = score.millisAt(score.endTick))
        file(entry).writeBytes(MusicXml.normalizedXml(bytes))
        entries.add(0, entry)
        save()
        return entry
    }
    fun remove(entry: LibraryEntry) {
        check(!file(entry).exists() || file(entry).delete()) { "無法刪除樂譜檔案" }
        entries.remove(entry)
        var playlistsChanged = false
        playlists.forEach { playlist ->
            if (playlist.trackIds.removeAll { it == entry.id }) {
                playlist.updated = System.currentTimeMillis()
                playlistsChanged = true
            }
        }
        save()
        if (playlistsChanged) savePlaylists()
    }
    fun mergeDurations(values: Map<String, Int>): Boolean {
        val latest = Library(context)
        var changed = false
        latest.entries.forEach { entry -> values[entry.id]?.let { duration ->
            if (entry.durationMillis == null) { entry.durationMillis = duration; changed = true }
        } }
        if (changed) latest.save()
        entries.clear(); entries += latest.entries
        return changed
    }
    fun save() {
        val array = JSONArray()
        entries.forEach { e -> array.put(JSONObject().apply {
            put("id", e.id); put("name", e.name); put("composer", e.composer); put("instruments", e.instruments)
            put("imported", e.imported); put("lastPlayed", e.lastPlayed); put("speed", e.speed)
            put("performanceStyle", e.performanceStyle.name)
            put("volumes", JSONArray(e.volumes)); put("programs", JSONArray(e.programs)); put("programOverrides", JSONArray(e.programOverrides))
            e.durationMillis?.let { put("durationMillis", it) }
        }) }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    fun createPlaylist(name: String): Playlist {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "播放清單名稱不能空白" }
        val now = System.currentTimeMillis()
        return Playlist(UUID.randomUUID().toString(), trimmed, now).also {
            playlists.add(0, it)
            savePlaylists()
        }
    }

    fun renamePlaylist(playlist: Playlist, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "播放清單名稱不能空白" }
        playlist.name = trimmed
        playlist.updated = System.currentTimeMillis()
        savePlaylists()
    }

    fun removePlaylist(playlist: Playlist) {
        playlists.removeAll { it.id == playlist.id }
        savePlaylists()
    }

    fun addToPlaylist(playlist: Playlist, trackIds: Collection<String>) {
        val valid = entries.mapTo(hashSetOf()) { it.id }
        trackIds.forEach { if (it in valid && it !in playlist.trackIds) playlist.trackIds.add(it) }
        playlist.updated = System.currentTimeMillis()
        savePlaylists()
    }

    fun removeFromPlaylist(playlist: Playlist, trackId: String) {
        if (playlist.trackIds.removeAll { it == trackId }) {
            playlist.updated = System.currentTimeMillis()
            savePlaylists()
        }
    }

    fun moveInPlaylist(playlist: Playlist, from: Int, to: Int) {
        if (from !in playlist.trackIds.indices || to !in playlist.trackIds.indices || from == to) return
        val id = playlist.trackIds.removeAt(from)
        playlist.trackIds.add(to, id)
        playlist.updated = System.currentTimeMillis()
        savePlaylists()
    }

    fun savePlaylists() {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("created", playlist.created)
                put("updated", playlist.updated)
                put("trackIds", JSONArray(playlist.trackIds))
            })
        }
        prefs.edit().putString("playlists", array.toString()).apply()
    }
}
