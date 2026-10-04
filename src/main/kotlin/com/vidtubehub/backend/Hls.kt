package com.vidtubehub.backend

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Chunked "merge while playing": yt-dlp resolves the separate video + audio stream URLs, ffmpeg remuxes them
 * (video copied, audio -> AAC) into 3s fMP4 HLS segments. The player starts as soon as the first segments exist
 * (~2-4s) while ffmpeg keeps producing faster than real time. The finished folder is the permanent server cache.
 */
class Hls(root: File, private val scope: CoroutineScope) {
    val base = File(root, "hls").apply { mkdirs() }
    private val states = ConcurrentHashMap<String, String>() // running | done | failed
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

    private fun k(id: String, q: Int) = "${id}_$q"
    fun dir(id: String, q: Int) = File(base, k(id, q))
    fun status(id: String, q: Int): String? = states[k(id, q)] ?: if (File(dir(id, q), ".done").exists()) "done" else null

    fun playlistReady(id: String, q: Int): Boolean {
        val d = dir(id, q); val pl = File(d, "index.m3u8")
        if (!pl.isFile || !File(d, "init.mp4").isFile) return false
        val t = runCatching { pl.readText() }.getOrDefault("")
        return t.contains("#EXT-X-ENDLIST") || "#EXTINF".toRegex().findAll(t).count() >= 2
    }

    @Synchronized fun ensure(id: String, q: Int) {
        val key = k(id, q)
        if (status(id, q).let { it == "running" || it == "done" }) return
        states[key] = "running"
        scope.launch(Dispatchers.IO) { runCatching { run(id, q, key) }.onFailure { states[key] = "failed" } }
    }

    private suspend fun run(id: String, q: Int, key: String) {
        val d = dir(id, q); d.deleteRecursively(); d.mkdirs()
        val sel = "bv*[height<=$q][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$q]+ba/b[height<=$q]/b"
        val urls = Ytdlp.run(listOf("--no-playlist", "-g", "-f", sel, Ytdlp.watchUrl(id)), 60)
            ?.lines()?.map { it.trim() }?.filter { it.startsWith("http") }.orEmpty()
        if (urls.isEmpty()) { states[key] = "failed"; return }
        val cmd = mutableListOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin")
        urls.forEach { cmd += listOf("-user_agent", UA, "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5", "-i", it) }
        if (urls.size >= 2) cmd += listOf("-map", "0:v:0", "-map", "1:a:0") else cmd += listOf("-map", "0:v:0", "-map", "0:a:0?")
        cmd += listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-f", "hls", "-hls_time", "3", "-hls_list_size", "0",
            "-hls_playlist_type", "event", "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
            "-hls_segment_filename", "${d.path}/seg_%05d.m4s", "${d.path}/index.m3u8")
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            while (p.isAlive) delay(500)
        } finally { if (p.isAlive) p.destroyForcibly() }
        if (p.exitValue() == 0) { File(d, ".done").writeText("1"); states.remove(key); trim() } else states[key] = "failed"
    }

    /** Keeps hls cache under VIDTUBE_HLS_GB (default 15), evicting least recently played folders. */
    private fun trim() {
        val cap = ((System.getenv("VIDTUBE_HLS_GB")?.toDoubleOrNull() ?: 15.0) * 1e9).toLong()
        val dirs = base.listFiles()?.filter { it.isDirectory && File(it, ".done").exists() }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (d in dirs) { if (total <= cap) break; total -= d.walkTopDown().filter { it.isFile }.sumOf { it.length() }; d.deleteRecursively() }
    }
}
