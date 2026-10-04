package com.vidtubehub.video.app.data

import kotlinx.serialization.Serializable

@Serializable data class Video(val id: String, val title: String, val channel: String = "", val thumbnailUrl: String = "", val duration: String = "", val views: String = "", val age: String = "", val streamUrl: String? = null)
@Serializable data class Page(val page: Int = 0, val hasMore: Boolean = false, val items: List<Video> = emptyList(), val categories: List<String>? = null, val hero: Video? = null)
@Serializable data class Option(val kind: String, val q: String, val label: String, val size: Long = 0)
@Serializable data class VideoInfo(val id: String, val title: String = "", val duration: Long = 0, val heights: List<Int> = emptyList(), val videoOptions: List<Option> = emptyList(), val audioOptions: List<Option> = emptyList())
@Serializable data class Playback(val id: String, val title: String = "", val duration: Long = 0, val video: String, val audio: String? = null, val height: Int = 0, val heights: List<Int> = emptyList())
@Serializable data class DlJob(val key: String, val status: String, val progress: Int = 0, val fileName: String? = null, val fileSize: Long = 0, val error: String? = null)

/** One row in the Downloads tab. state: queued | preparing | downloading | waiting | paused | done | failed */
@Serializable data class DlItem(
    val key: String, val id: String, val title: String, val thumb: String, val kind: String, val q: String, val label: String,
    val state: String = "queued", val progress: Int = 0, val bytes: Long = 0, val total: Long = 0, val path: String? = null, val error: String? = null,
)
