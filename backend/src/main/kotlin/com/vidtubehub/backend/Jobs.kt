package com.vidtubehub.backend

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side media cache + download queue.
 *  - visible=false jobs are silent "play caches" (evictable), visible=true are user downloads.
 *  - Jobs persist to jobs.json and are auto-resumed after a server restart.
 *  - yt-dlp runs with -c so partial files continue instead of restarting.
 */
class Jobs(root: File, private val scope: CoroutineScope) {
    val media = File(root, "media").apply { mkdirs() }
    private val store = File(root, "jobs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val items = ConcurrentHashMap<String, DownloadItem>()
    private val running = ConcurrentHashMap<String, Job>()
    private val procs = ConcurrentHashMap<String, Process>()
    private val slots = Semaphore(2)
    private val progRe = Regex("""PROG\s+([\d.]+)%""")

    init {
        runCatching { json.decodeFromString<List<DownloadItem>>(store.readText()) }.getOrNull()?.forEach { items[it.key] = it }
        items.values.filter { it.status in setOf("queued", "downloading", "merging") }.forEach { launchJob(it.key) }
    }

    fun key(id: String, kind: String, q: Int) = "${id}_${kind.first()}$q"
    fun list() = items.values.filter { it.visible }.sortedByDescending { it.createdAt }
    fun get(k: String) = items[k]
    fun fileOf(i: DownloadItem) = i.fileName?.let { File(media, it) }?.takeIf { it.isFile }
    fun ready(id: String, kind: String, q: Int) = items[key(id, kind, q)]?.takeIf { it.status == "ready" && fileOf(it) != null }
    fun bestReadyVideo(id: String) = items.values.filter { it.id == id && it.kind == "video" && it.status == "ready" && fileOf(it) != null }.maxByOrNull { it.quality }

    @Synchronized
    fun ensure(id: String, kind: String, q: Int, title: String, visible: Boolean): DownloadItem {
        val k = key(id, kind, q)
        val cur = items[k]
        if (cur != null) {
            val merged = if (visible && !cur.visible) cur.copy(visible = true) else cur
            items[k] = merged
            if (running[k]?.isActive == true || (merged.status == "ready" && fileOf(merged) != null)) { save(); return merged }
        }
        val base = cur ?: DownloadItem(k, id, title, "https://i.ytimg.com/vi/$id/hqdefault.jpg", kind, q, createdAt = System.currentTimeMillis())
        val fresh = base.copy(status = "queued", progress = 0, error = null, fileName = null, visible = visible || (cur?.visible ?: false))
        items[k] = fresh; save(); launchJob(k)
        return fresh
    }

    fun remove(k: String) {
        procs.remove(k)?.destroyForcibly()
        running.remove(k)?.cancel()
        items.remove(k)
        media.listFiles()?.filter { it.name.startsWith("$k.") }?.forEach { it.delete() } // also .part / .fNNN leftovers
        save()
    }

    private fun launchJob(k: String) { running[k] = scope.launch(Dispatchers.IO) { slots.withPermit { runJob(k) } } }

    private suspend fun runJob(k: String) {
        val item = items[k] ?: return
        update(k) { it.copy(status = "downloading", error = null) }
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            val err = runYtdlp(item)
            val file = findOutput(item)
            if (err == null && file != null) {
                update(k) { it.copy(status = "ready", progress = 100, fileName = file.name, sizeBytes = file.length()) }
                trim(); return
            }
            if (attempt >= 6) { update(k) { it.copy(status = "failed", error = err ?: "output file missing") }; return }
            update(k) { it.copy(status = "downloading", error = "retrying: ${err ?: "unknown"}") }
            delay(attempt * 5000L) // back-off; the next run resumes the .part files
        }
    }

    private fun findOutput(i: DownloadItem): File? {
        val re = Regex("^${Regex.escape(i.key)}\\.(mp4|mkv|webm|m4a|mp3)$")
        return media.listFiles()?.firstOrNull { re.matches(it.name) }
    }

    private fun runYtdlp(item: DownloadItem): String? {
        val out = "${media.path}/${item.key}.%(ext)s"
        val h = item.quality
        val sel = if (item.kind == "audio")
            listOf("-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "${item.quality}K")
        else
            listOf("-f", "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b", "--merge-output-format", "mp4")
        val cmd = listOf("yt-dlp", "--no-warnings", "--no-playlist", "-c", "--newline",
            "--retries", "10", "--fragment-retries", "10", "--retry-sleep", "3", "--concurrent-fragments", "4",
            "--progress-template", "download:PROG %(progress._percent_str)s", "-o", out) + sel + Ytdlp.watchUrl(item.id)
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        procs[item.key] = p
        var last: String? = null
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                val m = progRe.find(line)
                if (m != null) {
                    val pct = m.groupValues[1].toDoubleOrNull()?.toInt() ?: return@forEachLine
                    val cur = items[item.key] ?: return@forEachLine
                    val np = minOf(95, maxOf(cur.progress, pct)) // two streams are fetched (video, audio): keep monotonic
                    if (np != cur.progress) update(item.key) { it.copy(progress = np, status = "downloading") }
                } else {
                    if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") || line.startsWith("[VideoRemuxer]"))
                        update(item.key) { it.copy(status = "merging", progress = 97) }
                    if (line.isNotBlank()) last = line
                }
            }
            val code = p.waitFor()
            return if (code == 0) null else (last ?: "yt-dlp exited with $code")
        } finally { procs.remove(item.key); if (p.isAlive) p.destroyForcibly() }
    }

    private fun update(k: String, f: (DownloadItem) -> DownloadItem) { items.computeIfPresent(k) { _, v -> f(v) }; save() }

    @Synchronized private fun save() {
        runCatching {
            val tmp = File(store.path + ".tmp")
            tmp.writeText(json.encodeToString(items.values.toList()))
            tmp.renameTo(store)
        }
    }

    /** Keeps the cache under VIDTUBE_CACHE_GB (default 20) by evicting oldest silent play-caches. */
    private fun trim() {
        val cap = (System.getenv("VIDTUBE_CACHE_GB")?.toDoubleOrNull() ?: 20.0) * 1e9
        var total = media.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= cap) return
        items.values.filter { !it.visible && it.status == "ready" }.sortedBy { fileOf(it)?.lastModified() ?: 0L }.forEach {
            if (total > cap) { total -= it.sizeBytes; remove(it.key) }
        }
    }
}
