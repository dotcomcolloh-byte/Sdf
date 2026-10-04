package com.vidtubehub.video.app.data

import android.content.Context
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as CN
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vidtubehub.video.app.auth.Account
import java.security.MessageDigest

const val PAGE_SIZE = 20

@Serializable data class Feed(val categories: List<String>, val hero: Video?, val trending: List<Video>, val defaultQuery: String = "")
@Serializable data class FormatOption(val kind: String, val quality: Int, val label: String, val sizeBytes: Long? = null)
@Serializable data class Formats(val id: String, val video: List<FormatOption>, val audio: List<FormatOption>)
@Serializable data class StreamInfo(val url: String?, val source: String, val quality: Int, val progress: Int = 0)
@Serializable data class ServerJob(val key: String, val id: String, val status: String, val progress: Int = 0, val fileName: String? = null, val sizeBytes: Long = 0, val error: String? = null)

@Serializable data class UserInfo(val id: String, val email: String, val name: String, val picture: String? = null)
@Serializable data class LoginResult(val token: String, val user: UserInfo)
@Serializable data class TierInfo(val minDays: Int, val maxDays: Int, val perDayCents: Int, val viewsPerDay: Int)
@Serializable data class Pricing(val currency: String, val tiers: List<TierInfo>, val minDays: Int = 1, val maxDays: Int = 90, val minAdSeconds: Int = 30)
@Serializable data class Ad(val id: String, val title: String, val url: String, val description: String = "", val days: Int, val priceCents: Int, val currency: String, val targetViews: Int,
    val delivered: Int = 0, val impressions: Int = 0, val clicks: Int = 0, val status: String = "draft", val videoStatus: String = "none", val durationSec: Int = 0,
    val thumbFile: String? = null, val expiresAt: Long = 0, val error: String? = null)
@Serializable data class AdPublic(val id: String, val title: String, val description: String, val url: String, val thumbnailUrl: String, val videoUrl: String, val durationSec: Int, val token: String)
@Serializable data class ViewResult(val counted: Boolean, val pass: String? = null)
@Serializable data class PayInit(val authorizationUrl: String, val reference: String)
@Serializable data class CreateAdReq(val title: String, val url: String, val description: String, val days: Int)
@Serializable data class ViewReq(val adId: String, val token: String, val watchedSec: Int, val placement: String)
class ApiException(message: String, val code: Int) : IOException(message)

/** [stale] = served from the on-device cache because the network/server failed. */
class Loaded<T>(val value: T, val stale: Boolean)

class VideoRepository(private val ctx: Context) {
    companion object { lateinit var instance: VideoRepository }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(ctx.cacheDir, "api").apply { mkdirs() }
    private val client = HttpClient(Android) {
        install(CN) { json(json) }
        install(DefaultRequest) { header("X-Device-Id", Settings.deviceId) }
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
    }

    fun absolute(path: String) = if (path.startsWith("http")) path else Settings.baseUrl + path
    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Network first; on failure fall back to the last good response on disk (offline browsing). */
    private suspend inline fun <reified T> cachedGet(path: String, params: Map<String, String> = emptyMap()): Loaded<T> {
        val f = File(dir, sha(Settings.baseUrl + path + params.toSortedMap()) + ".json")
        try {
            val resp = client.get(Settings.baseUrl + path) { params.forEach { (k, v) -> parameter(k, v) } }
            if (!resp.status.isSuccess()) throw IOException("HTTP ${resp.status.value}")
            val text = resp.bodyAsText()
            val v = json.decodeFromString<T>(text)
            runCatching { f.writeText(text) }
            return Loaded(v, false)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val cached = runCatching { json.decodeFromString<T>(f.readText()) }.getOrNull() ?: throw e
            return Loaded(cached, true)
        }
    }

    suspend fun feed() = cachedGet<Feed>("/api/feed")
    suspend fun videos(q: String, page: Int) = cachedGet<Page>("/api/videos", mapOf("q" to q, "page" to "$page", "size" to "$PAGE_SIZE"))
    suspend fun formats(id: String) = cachedGet<Formats>("/api/formats/$id")

    suspend fun stream(id: String, quality: Int, title: String = ""): StreamInfo {
        val response = client.get("${Settings.baseUrl}/api/stream/$id") { parameter("quality", quality); if (title.isNotBlank()) parameter("title", title) }
        if (!response.status.isSuccess()) throw ApiException("HTTP ${response.status.value}", response.status.value)
        return response.body()
    }

    private suspend fun HttpResponse.ok(): HttpResponse { if (!status.isSuccess()) throw ApiException(bodyAsText().take(200).ifBlank { "HTTP ${status.value}" }, status.value); return this }
    private fun HttpRequestBuilder.authed() { Account.token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    suspend fun requestDownload(id: String, title: String, kind: String, quality: Int, pass: String? = null): ServerJob =
        client.post("${Settings.baseUrl}/api/download") { parameter("id", id); parameter("title", title); parameter("kind", kind); parameter("quality", quality); pass?.let { parameter("pass", it) } }.ok().body()

    suspend fun serverJob(key: String): ServerJob? =
        client.get("${Settings.baseUrl}/api/downloads/$key").let { if (it.status == HttpStatusCode.NotFound) null else if (it.status.isSuccess()) it.body() else throw IOException("HTTP ${it.status.value}") }

    // ---------- accounts / campaigns ----------
    suspend fun login(idToken: String): LoginResult = client.post("${Settings.baseUrl}/api/auth/google") { contentType(ContentType.Application.Json); setBody(mapOf("idToken" to idToken)) }.ok().body()
    suspend fun pricing(): Pricing = client.get("${Settings.baseUrl}/api/ads/pricing").ok().body()
    suspend fun myAds(): List<Ad> = client.get("${Settings.baseUrl}/api/ads/mine") { authed() }.ok().body()
    suspend fun createAd(r: CreateAdReq): Ad = client.post("${Settings.baseUrl}/api/ads") { authed(); contentType(ContentType.Application.Json); setBody(r) }.ok().body()
    suspend fun payAd(id: String): PayInit = client.post("${Settings.baseUrl}/api/ads/$id/pay") { authed() }.ok().body()
    suspend fun verifyAd(id: String): Ad = client.post("${Settings.baseUrl}/api/ads/$id/verify") { authed() }.ok().body()
    suspend fun adAction(id: String, action: String) { client.post("${Settings.baseUrl}/api/ads/$id/$action") { authed() }.ok() }
    suspend fun deleteAd(id: String) { client.delete("${Settings.baseUrl}/api/ads/$id") { authed() }.ok() }

    /** Streams the picked video to the server (PUT raw body). The server re-checks the >=30s rule itself. */
    suspend fun uploadAdVideo(adId: String, uri: android.net.Uri, onProgress: (Float) -> Unit): Ad = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val conn = URL("${Settings.baseUrl}/api/ads/$adId/video").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"; conn.doOutput = true; conn.connectTimeout = 15_000; conn.readTimeout = 300_000
        if (size > 0) conn.setFixedLengthStreamingMode(size) else conn.setChunkedStreamingMode(64 * 1024)
        conn.setRequestProperty("Authorization", "Bearer ${Account.token}"); conn.setRequestProperty("X-Device-Id", Settings.deviceId); conn.setRequestProperty("Content-Type", "video/mp4")
        var sent = 0L
        cr.openInputStream(uri)!!.use { inp -> conn.outputStream.use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); sent += n; if (size > 0) onProgress(sent.toFloat() / size) }
        } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw ApiException(text.take(200), code)
        json.decodeFromString<Ad>(text)
    }

    // ---------- ad delivery ----------
    suspend fun serveAds(placement: String, n: Int): List<AdPublic> =
        runCatching { client.get("${Settings.baseUrl}/api/ads/serve") { parameter("placement", placement); parameter("n", n); authed() }.ok().body<List<AdPublic>>() }.getOrDefault(emptyList())
    suspend fun reportView(ad: AdPublic, watchedSec: Int, placement: String): ViewResult? =
        runCatching { client.post("${Settings.baseUrl}/api/ads/view") { authed(); contentType(ContentType.Application.Json); setBody(ViewReq(ad.id, ad.token, watchedSec, placement)) }.ok().body<ViewResult>() }.getOrNull()
    suspend fun reportClick(adId: String) { runCatching { client.post("${Settings.baseUrl}/api/ads/$adId/click") } }

    fun clearApiCache() { dir.listFiles()?.forEach { it.delete() } }
}
