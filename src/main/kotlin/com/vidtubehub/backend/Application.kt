package com.vidtubehub.backend

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val root = File(System.getenv("VIDTUBE_DATA") ?: "cache").apply { mkdirs() }
private val pagesDir = File(root, "pages").apply { mkdirs() }
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val jobs by lazy { Jobs(root, appScope) }
private val hls by lazy { Hls(root, appScope) }
private val hlsFileRe = Regex("^(index\\.m3u8|init\\.mp4|seg_\\d{5}\\.m4s)$")
private val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val categories = listOf("All", "Music", "Gaming", "Travel", "Food", "Tech", "News")
private var feed = Feed(categories, null, emptyList())
private val feedLock = Mutex()
private val pageCache = ConcurrentHashMap<String, Pair<Long, Page>>()
private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
private const val DAY = 24 * 60 * 60 * 1000L
private val fileRe = Regex("^[A-Za-z0-9_-]{6,40}\\.(mp4|mkv|webm|m4a|mp3)$")
private val keyRe = Regex("^[A-Za-z0-9_-]{6,40}$")

fun main() { embeddedServer(Netty, port = (System.getenv("PORT") ?: "8080").toInt(), host = "0.0.0.0") { module() }.start(wait = true) }

fun Application.module() {
    install(ContentNegotiation) { json(appJson) }
    install(PartialContent)      // Range support for cached files => seeking + resume
    install(AutoHeadResponse)
    AdsRuntime.init(root, appScope)
    jobs.list() // start job manager (auto-resumes unfinished downloads)
    val feedFile = File(root, "feed.json")
    runCatching { feed = appJson.decodeFromString<Feed>(feedFile.readText()).copy(categories = categories) }
    appScope.launch { // never blocks startup; a failed refresh keeps the old feed
        var wait = if (feed.trending.isNotEmpty()) (DAY - (System.currentTimeMillis() - feedFile.lastModified())).coerceAtLeast(0) else 0L
        while (isActive) { delay(wait); refreshFeed(); wait = DAY }
    }

    routing {
        adRoutes(appScope)
        get("/health") { call.respond(mapOf("status" to "ok")) }

        get("/api/feed") {
            if (feed.trending.isEmpty()) refreshFeed()
            call.respond(feed)
        }

        get("/api/videos") {
            val q = call.request.queryParameters["q"]?.trim().orEmpty().ifBlank { DEFAULT_QUERY }.take(120)
            val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 0).coerceIn(0, 50)
            val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: PAGE_SIZE).coerceIn(5, 30)
            val res = cachedPage(q, page, size)
            if (res == null) call.respondText("upstream unavailable", status = HttpStatusCode.BadGateway) else call.respond(res)
        }

        get("/api/formats/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!Ytdlp.validId(id)) return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val f = Ytdlp.formats(id)
            if (f == null) call.respondText("formats unavailable", status = HttpStatusCode.NotFound) else call.respond(f)
        }

        // Decides HOW to play: finished download > HLS (chunked merge, starts in ~2-4s) > instant proxy fallback
        get("/api/stream/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!Ytdlp.validId(id)) return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val q = call.request.queryParameters["quality"]?.toIntOrNull() ?: 0
            val want = if (q == 0) 720 else q.coerceIn(144, 4320)
            val hit = jobs.ready(id, "video", want) ?: if (q == 0) jobs.bestReadyVideo(id) else null
            if (hit != null) return@get call.respond(StreamInfo("/api/file/${hit.fileName}", "cache", hit.quality, 100))
            hls.ensure(id, want)
            // hold the request briefly so the app gets a playable URL in ONE round trip
            withTimeoutOrNull(12_000) { while (!hls.playlistReady(id, want) && hls.status(id, want) != "failed") delay(200) }
            if (hls.playlistReady(id, want)) return@get call.respond(StreamInfo("/api/hls/$id/$want/index.m3u8", "hls", want, 100))
            if (hls.status(id, want) == "failed") {
                if (q == 0) Ytdlp.progressive(id, 360)?.let { return@get call.respond(StreamInfo("/api/proxy/$id?q=360", "proxy", it.height, 0)) }
                return@get call.respondText("stream unavailable", status = HttpStatusCode.BadGateway)
            }
            call.respond(StreamInfo(null, "preparing", want, 0))
        }

        get("/api/hls/{id}/{q}/{file}") {
            val id = call.parameters["id"].orEmpty(); val q = call.parameters["q"]?.toIntOrNull()
            val file = call.parameters["file"].orEmpty()
            if (!Ytdlp.validId(id) || q == null || !hlsFileRe.matches(file)) return@get call.respondText("bad request", status = HttpStatusCode.BadRequest)
            val d = hls.dir(id, q); val f = File(d, file)
            if (!f.isFile) return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            d.setLastModified(System.currentTimeMillis())
            if (file.endsWith(".m3u8")) {
                var t = f.readText()
                if (!t.contains("#EXT-X-START")) t = t.replaceFirst("#EXTM3U", "#EXTM3U\n#EXT-X-START:TIME-OFFSET=0") // start at 0, not live edge
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(t, ContentType("application", "vnd.apple.mpegurl"))
            } else call.respond(LocalFileContent(f, if (file == "init.mp4") ContentType.Video.MP4 else ContentType("video", "iso.segment")))
        }

        // Pipes the upstream progressive stream (fixes IP-bound/expiring URLs) with Range forwarding.
        get("/api/proxy/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!Ytdlp.validId(id)) return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val q = call.request.queryParameters["q"]?.toIntOrNull() ?: 360
            for (attempt in 0..1) {
                val prog = Ytdlp.progressive(id, q) ?: return@get call.respondText("stream unavailable", status = HttpStatusCode.NotFound)
                val rb = HttpRequest.newBuilder(URI(prog.url)).header("User-Agent", UA)
                call.request.headers[HttpHeaders.Range]?.let { rb.header("Range", it) }
                val resp = withContext(Dispatchers.IO) { http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream()) }
                val code = resp.statusCode()
                if (code in listOf(403, 404, 410) && attempt == 0) { resp.body().close(); Ytdlp.invalidate(id, q); continue }
                resp.headers().firstValue("Content-Range").orElse(null)?.let { call.response.header(HttpHeaders.ContentRange, it) }
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                val len = resp.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else null }
                val body = resp.body()
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val status = HttpStatusCode.fromValue(code)
                    override val contentType = ContentType.Video.MP4
                    override val contentLength = len
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        withContext(Dispatchers.IO) {
                            body.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) { val n = input.read(buf); if (n < 0) break; channel.writeFully(buf, 0, n) }
                            }
                        }
                    }
                })
                return@get
            }
        }

        get("/api/file/{name}") {
            val name = call.parameters["name"].orEmpty()
            if (!fileRe.matches(name)) return@get call.respondText("bad name", status = HttpStatusCode.BadRequest)
            val f = File(jobs.media, name)
            if (!f.isFile) return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            f.setLastModified(System.currentTimeMillis()) // LRU touch
            val type = when (f.extension) {
                "mp4" -> ContentType.Video.MP4; "webm" -> ContentType("video", "webm"); "mkv" -> ContentType("video", "x-matroska")
                "m4a" -> ContentType("audio", "mp4"); else -> ContentType.Audio.MPEG
            }
            call.respond(LocalFileContent(f, type))
        }

        post("/api/download") {
            val p = call.request.queryParameters
            val id = p["id"].orEmpty()
            if (!Ytdlp.validId(id)) return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            // Ad gate: while any paid ad is live, a new download needs a single-use pass earned by watching one (device-bound, 10 min).
            val dev = call.request.headers["X-Device-Id"]?.take(64)?.takeIf { it.length >= 8 } ?: call.request.local.remoteAddress
            if (!AdsRuntime.gateSatisfied(p["pass"], dev)) return@post call.respondText("ad_required", status = HttpStatusCode.Forbidden)
            val kind = p["kind"] ?: "video"
            if (kind != "video" && kind != "audio") return@post call.respondText("bad kind", status = HttpStatusCode.BadRequest)
            val q = (p["quality"]?.toIntOrNull() ?: if (kind == "audio") 128 else 720).let { if (kind == "audio") it.coerceIn(32, 320) else it.coerceIn(144, 4320) }
            call.respond(jobs.ensure(id, kind, q, p["title"]?.take(200) ?: id, visible = true))
        }
        get("/api/downloads") { call.respond(jobs.list()) }
        get("/api/downloads/{key}") {
            val it = jobs.get(call.parameters["key"].orEmpty())
            if (it == null) call.respondText("not found", status = HttpStatusCode.NotFound) else call.respond(it)
        }
        delete("/api/downloads/{key}") {
            val k = call.parameters["key"].orEmpty()
            if (!keyRe.matches(k)) return@delete call.respondText("bad key", status = HttpStatusCode.BadRequest)
            jobs.remove(k); call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun refreshFeed() = feedLock.withLock {
    val page = Ytdlp.search(DEFAULT_QUERY, 0, PAGE_SIZE) ?: return@withLock
    if (page.items.isEmpty()) return@withLock // never overwrite a good feed with an empty one
    feed = feed.copy(hero = page.items.first(), trending = page.items)
    File(root, "feed.json").writeText(appJson.encodeToString(feed))
}

private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Fresh 6h memory cache -> yt-dlp -> stale disk copy (so the API still answers when YouTube fails). */
private suspend fun cachedPage(q: String, page: Int, size: Int): Page? {
    val k = "$q|$page|$size"; val now = System.currentTimeMillis()
    pageCache[k]?.let { if (now - it.first < 6 * 3_600_000L) return it.second }
    val f = File(pagesDir, sha(k) + ".json")
    val fresh = Ytdlp.search(q, page, size)
    if (fresh != null && fresh.items.isNotEmpty()) {
        pageCache[k] = now to fresh
        runCatching { f.writeText(appJson.encodeToString(fresh)) }
        return fresh
    }
    return pageCache[k]?.second ?: runCatching { appJson.decodeFromString<Page>(f.readText()) }.getOrNull() ?: fresh
}
