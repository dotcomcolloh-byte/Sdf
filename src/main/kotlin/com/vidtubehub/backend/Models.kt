package com.vidtubehub.backend

import kotlinx.serialization.Serializable

const val PAGE_SIZE = 20
const val DEFAULT_QUERY = "travel music gaming news"

@Serializable data class Video(val id: String, val title: String, val channel: String, val thumbnailUrl: String, val duration: String, val views: String, val age: String, val streamUrl: String? = null)
@Serializable data class Page(val items: List<Video>, val page: Int, val hasMore: Boolean)
@Serializable data class Feed(val categories: List<String>, val hero: Video?, val trending: List<Video>, val defaultQuery: String = DEFAULT_QUERY)
@Serializable data class FormatOption(val kind: String, val quality: Int, val label: String, val sizeBytes: Long? = null)
@Serializable data class Formats(val id: String, val video: List<FormatOption>, val audio: List<FormatOption>)
/** source: cache (file on disk) | proxy (piped progressive stream) | preparing (url == null, poll again) */
@Serializable data class StreamInfo(val url: String?, val source: String, val quality: Int, val progress: Int = 0)
@Serializable data class DownloadItem(
    val key: String, val id: String, val title: String, val thumbnailUrl: String,
    val kind: String, val quality: Int, val progress: Int = 0, val status: String = "queued",
    val fileName: String? = null, val sizeBytes: Long = 0, val error: String? = null,
    val visible: Boolean = true, val createdAt: Long = 0
)
