package com.vidtubehub.video.app.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** status: queued | preparing | downloading | waiting | done | failed */
@Serializable data class LocalDownload(
    val key: String, val id: String, val title: String, val thumbnailUrl: String, val kind: String, val quality: Int,
    val status: String = "queued", val progress: Int = 0, val bytes: Long = 0, val total: Long = 0,
    val path: String? = null, val pass: String? = null, val error: String? = null, val ts: Long = System.currentTimeMillis()
)

object DownloadStore {
    private lateinit var sp: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<LocalDownload>>(emptyList())
    val items: StateFlow<List<LocalDownload>> = _items

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        _items.value = runCatching { json.decodeFromString<List<LocalDownload>>(sp.getString("items", "[]")!!) }.getOrDefault(emptyList())
    }
    fun get(key: String) = _items.value.firstOrNull { it.key == key }
    @Synchronized fun upsert(d: LocalDownload) {
        _items.value = (_items.value.filterNot { it.key == d.key } + d).sortedByDescending { it.ts }
        sp.edit().putString("items", json.encodeToString(_items.value)).apply()
    }
    @Synchronized fun remove(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
        sp.edit().putString("items", json.encodeToString(_items.value)).apply()
    }
}
