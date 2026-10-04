package com.vidtubehub.backend

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Thin, safe wrapper around the yt-dlp CLI (public videos only). */
object Ytdlp {
    private val ID = Regex("^[A-Za-z0-9_-]{6,20}$")
    private val json = Json { ignoreUnknownKeys = true }
    fun validId(id: String) = ID.matches(id)
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private data class RunAttempt(
        val output: String? = null,
        val category: String? = null,
        val exitCode: Int? = null,
        val stderrBytes: Int = 0,
        val exception: String? = null
    )

    private val alternateClientCategories = setOf(
        "empty_output", "http_403", "po_token_required", "youtube_auth_or_bot_check", "js_challenge_failed"
    )

    /** Prefer the token-free embedded client, then try yt-dlp's documented token-free android_vr client for eligible failures. */
    suspend fun run(args: List<String>, timeoutSec: Long = 90): String? = withContext(Dispatchers.IO) {
        val primary = runForClient(args, timeoutSec, "web_embedded")
        if (!primary.output.isNullOrBlank()) return@withContext primary.output
        if (primary.category !in alternateClientCategories) {
            logFailure("web_embedded", primary)
            return@withContext null
        }
        val fallback = runForClient(args, timeoutSec, "android_vr")
        if (!fallback.output.isNullOrBlank()) {
            System.err.println("YTDLP_DIAG category=client_fallback primary=${primary.category} selected=android_vr")
            return@withContext fallback.output
        }
        System.err.println("YTDLP_DIAG category=all_clients_failed primary=${primary.category ?: "unknown"} fallback=${fallback.category ?: "unknown"} primary_exit=${primary.exitCode ?: -1} fallback_exit=${fallback.exitCode ?: -1} primary_stderr_bytes=${primary.stderrBytes} fallback_stderr_bytes=${fallback.stderrBytes}")
        null
    }

    private suspend fun runForClient(args: List<String>, timeoutSec: Long, client: String): RunAttempt = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(listOf("yt-dlp", "--no-warnings", "--js-runtimes", "deno:/usr/local/bin/deno", "--extractor-args", "youtube:player_client=$client") + args).start()
            val stdout = async { p.inputStream.bufferedReader().use { it.readText() } }
            val stderr = async { readBounded(p.errorStream) }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly(); stdout.cancel(); stderr.cancel()
                RunAttempt(category = "process_timeout")
            } else {
                val err = stderr.await()
                val exit = p.exitValue()
                val bytes = err.toByteArray().size
                if (exit == 0) {
                    val output = stdout.await()
                    if (output.isBlank()) RunAttempt(category = "empty_output", exitCode = exit, stderrBytes = bytes)
                    else RunAttempt(output = output, exitCode = exit, stderrBytes = bytes)
                } else {
                    stdout.cancel()
                    RunAttempt(category = failureCategory(err), exitCode = exit, stderrBytes = bytes)
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            RunAttempt(category = "process_start", exception = e.javaClass.simpleName)
        }
    }

    private fun logFailure(client: String, result: RunAttempt) {
        System.err.println("YTDLP_DIAG category=${result.category ?: "unknown"} client=$client exit=${result.exitCode ?: -1} stderr_bytes=${result.stderrBytes} exception=${result.exception ?: "none"}")
    }

    // Drain stderr to prevent subprocess blocking, but retain only a small prefix for local classification.
    private fun readBounded(stream: InputStream, maxBytes: Int = 16 * 1024): String {
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var saved = 0
        stream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                val copy = minOf(n, maxBytes - saved)
                if (copy > 0) { kept.write(buffer, 0, copy); saved += copy }
            }
        }
        return String(kept.toByteArray(), StandardCharsets.UTF_8)
    }

    // Never print remote stderr: it may contain signed URLs or attacker-controlled text.
    private fun failureCategory(stderr: String): String {
        val s = stderr.lowercase(Locale.ROOT)
        return when {
            "po token" in s || "potoken" in s -> "po_token_required"
            "confirm you're not a bot" in s || "sign in to confirm" in s -> "youtube_auth_or_bot_check"
            Regex("\\b403\\b").containsMatchIn(s) -> "http_403"
            Regex("\\b429\\b").containsMatchIn(s) || "too many requests" in s -> "http_429"
            "no supported javascript runtime" in s || "javascript runtime" in s && "not found" in s -> "missing_js_runtime"
            "challenge solving failed" in s || "n challenge" in s || "js challenge" in s -> "js_challenge_failed"
            "video unavailable" in s || "private video" in s || "not available in your country" in s -> "video_unavailable_or_restricted"
            "timed out" in s || "timeout" in s -> "network_timeout"
            "unable to download webpage" in s || "network is unreachable" in s || "connection reset" in s -> "youtube_network_error"
            else -> "extractor_error"
        }
    }

    private fun diagnose(category: String) {
        System.err.println("YTDLP_DIAG category=$category")
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
        val o = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull() ?: run {
            diagnose("invalid_metadata_json")
            return null
        }
        val duration = o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val fs = o["formats"]?.jsonArray?.map { it.jsonObject } ?: run {
            diagnose("metadata_without_formats")
            return null
        }
        fun size(f: JsonObject) = f["filesize"]?.jsonPrimitive?.longOrNull ?: f["filesize_approx"]?.jsonPrimitive?.longOrNull
        val audioOnly = fs.filter { it.str("vcodec") == "none" && it.str("acodec") !in listOf(null, "none") }
        val audioSize = audioOnly.mapNotNull(::size).maxOrNull() ?: 0L
        val maxAbr = audioOnly.mapNotNull { it["abr"]?.jsonPrimitive?.doubleOrNull }.maxOrNull() ?: 128.0
        val video = fs.filter { it.str("vcodec") !in listOf(null, "none") && (it["height"]?.jsonPrimitive?.intOrNull ?: 0) >= 144 }
            .groupBy { it["height"]!!.jsonPrimitive.int }
            .entries.sortedByDescending { it.key }
            .map { (h, list) -> FormatOption("video", h, "${h}p", list.mapNotNull(::size).maxOrNull()?.plus(audioSize)) }
        if (video.isEmpty()) {
            diagnose("no_usable_video_formats")
            return null
        }
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
        val output = run(listOf("--no-playlist", "-f", "b[height<=$q][ext=mp4]/b[ext=mp4]", "--print", "%(height)s|%(url)s", watchUrl(id)), 60) ?: return null
        val line = output.lineSequence().firstOrNull { it.contains("|http") } ?: run {
            diagnose("no_progressive_format")
            return null
        }
        val h = line.substringBefore('|').trim().toIntOrNull() ?: return null
        return Prog(line.substringAfter('|').trim(), h).also { progCache[k] = System.currentTimeMillis() to it }
    }
    fun invalidate(id: String, q: Int) { progCache.remove("$id|$q") }
}
