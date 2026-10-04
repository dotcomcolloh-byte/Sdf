package com.vidtubehub.video.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class HistoryEntry(val video: Video, val watchedAt: Long, val positionMs: Long = 0, val durationMs: Long = 0)

/** Persistent watch history (newest first, max 200) with resume positions. */
object HistoryStore {
    private lateinit var sp: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
        _items.value = runCatching { json.decodeFromString<List<HistoryEntry>>(sp.getString("items", "[]")!!) }.getOrDefault(emptyList())
    }
    fun get(id: String) = _items.value.firstOrNull { it.video.id == id }
    @Synchronized fun record(v: Video) {
        val old = get(v.id)
        // downloaded-file plays carry placeholder metadata: keep the richer existing entry
        val video = if (old != null && v.channel == "Downloaded") old.video else v
        save(listOf(HistoryEntry(video, System.currentTimeMillis(), old?.positionMs ?: 0, old?.durationMs ?: 0)) + _items.value.filterNot { it.video.id == v.id })
    }
    @Synchronized fun progress(id: String, pos: Long, dur: Long) {
        val e = get(id) ?: return
        val p = if (dur > 0 && pos > dur * 95 / 100) 0 else pos // finished => restart next time
        if (p == e.positionMs && dur == e.durationMs) return
        save(_items.value.map { if (it.video.id == id) it.copy(positionMs = p, durationMs = dur) else it })
    }
    @Synchronized fun remove(id: String) = save(_items.value.filterNot { it.video.id == id })
    @Synchronized fun clear() = save(emptyList())
    private fun save(l: List<HistoryEntry>) { _items.value = l.take(200); sp.edit().putString("items", json.encodeToString(_items.value)).apply() }
}
