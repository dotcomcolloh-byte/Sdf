package com.vidtubehub.backend

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Thin, safe wrapper around the yt-dlp CLI (public videos only). */
object Ytdlp {
    private val ID = Regex("^[A-Za-z0-9_-]{6,20}$")
    private val json = Json { ignoreUnknownKeys = true }
    fun validId(id: String) = ID.matches(id)
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Runs yt-dlp, returns stdout or null. stderr is DISCARDED so warnings can't corrupt JSON. */
    suspend fun run(args: List<String>, timeoutSec: Long = 90): String? = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(listOf("yt-dlp", "--no-warnings") + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val reader = async { p.inputStream.bufferedReader().use { it.readText() } }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); reader.cancel(); null }
            else if (p.exitValue() == 0) reader.await() else { reader.cancel(); null }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    // ---------- search / paging ----------
    suspend fun search(q: String, page: Int, size: Int): Page? {
        val start = page * size + 1
        val end = start + size - 1
        val out = run(listOf("--flat-playlist", "--dump-single-json", "--playlist-items", "$start-$end", "ytsearch$end:$q")) ?: return null
        val entries = runCatching { json.parseToJsonElement(out).jsonObject["entries"]?.jsonArray }.getOrNull() ?: return null
        val items = entries.mapNotNull { runCatching { toVideo(it.jsonObject) }.getOrNull() }
        return Page(items, page, entries.size >= size)
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    private fun toVideo(o: JsonObject): Video? {
        val id = o.str("id")?.takeIf { validId(it) } ?: return null
        val live = o.str("live_status") == "is_live"
        return Video(
            id, o.str("title") ?: "Untitled", o.str("channel") ?: o.str("uploader") ?: "Unknown channel",
            "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            if (live) "LIVE" else fmtDuration(o["duration"]?.jsonPrimitive?.doubleOrNull),
            fmtViews(o["view_count"]?.jsonPrimitive?.longOrNull), fmtAge(o), watchUrl(id)
        )
    }

    private fun fmtDuration(sec: Double?): String {
        val s = (sec ?: 0.0).toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmtViews(n: Long?): String = when {
        n == null -> ""
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB views", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM views", n / 1e6)
        n >= 1_000 -> String.format(Locale.US, "%.1fK views", n / 1e3)
        else -> "$n views"
    }

    private fun fmtAge(o: JsonObject): String {
        val ts = o["timestamp"]?.jsonPrimitive?.longOrNull ?: o.str("upload_date")?.let {
            runCatching { java.time.LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() }.getOrNull()
        } ?: return ""
        val d = (System.currentTimeMillis() / 1000 - ts) / 86400
        return when { d < 1 -> "today"; d < 7 -> "${d}d ago"; d < 30 -> "${d / 7}w ago"; d < 365 -> "${d / 30}mo ago"; else -> "${d / 365}y ago" }
    }

    // ---------- available qualities ----------
    private val fmtCache = ConcurrentHashMap<String, Pair<Long, Formats>>()

    suspend fun formats(id: String): Formats? {
        fmtCache[id]?.let { if (System.currentTimeMillis() - it.first < 30 * 60_000) return it.second }
        val out = run(listOf("--no-playlist", "-J", watchUrl(id))) ?: return null
        val o = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return null
        val duration = o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val fs = o["formats"]?.jsonArray?.map { it.jsonObject } ?: return null
        fun size(f: JsonObject) = f["filesize"]?.jsonPrimitive?.longOrNull ?: f["filesize_approx"]?.jsonPrimitive?.longOrNull
        val audioOnly = fs.filter { it.str("vcodec") == "none" && it.str("acodec") !in listOf(null, "none") }
        val audioSize = audioOnly.mapNotNull(::size).maxOrNull() ?: 0L
        val maxAbr = audioOnly.mapNotNull { it["abr"]?.jsonPrimitive?.doubleOrNull }.maxOrNull() ?: 128.0
        val video = fs.filter { it.str("vcodec") !in listOf(null, "none") && (it["height"]?.jsonPrimitive?.intOrNull ?: 0) >= 144 }
            .groupBy { it["height"]!!.jsonPrimitive.int }
            .entries.sortedByDescending { it.key }
            .map { (h, list) -> FormatOption("video", h, "${h}p", list.mapNotNull(::size).maxOrNull()?.plus(audioSize)) }
        if (video.isEmpty()) return null
        val audio = listOf(320, 192, 128, 64).filter { it <= maxOf(128.0, maxAbr + 32) }
            .map { FormatOption("audio", it, "$it kbps MP3", (it * 1000L / 8 * duration).toLong()) }
        return Formats(id, video, audio).also { fmtCache[id] = System.currentTimeMillis() to it }
    }

    // ---------- progressive (audio+video in one file) URL for instant playback ----------
    data class Prog(val url: String, val height: Int)
    private val progCache = ConcurrentHashMap<String, Pair<Long, Prog>>()

    suspend fun progressive(id: String, q: Int): Prog? {
        val k = "$id|$q"
        progCache[k]?.let { if (System.currentTimeMillis() - it.first < 5 * 60_000) return it.second }
        val line = run(listOf("--no-playlist", "-f", "b[height<=$q][ext=mp4]/b[ext=mp4]", "--print", "%(height)s|%(url)s", watchUrl(id)), 60)
            ?.lineSequence()?.firstOrNull { it.contains("|http") } ?: return null
        val h = line.substringBefore('|').trim().toIntOrNull() ?: return null
        return Prog(line.substringAfter('|').trim(), h).also { progCache[k] = System.currentTimeMillis() to it }
    }
    fun invalidate(id: String, q: Int) { progCache.remove("$id|$q") }
}
