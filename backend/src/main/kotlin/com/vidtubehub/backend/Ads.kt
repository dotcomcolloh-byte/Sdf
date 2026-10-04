package com.vidtubehub.backend

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val DAY_MS = 86_400_000L
const val MIN_AD_SECONDS = 30
const val MAX_AD_SECONDS = 180

// ---------------- pricing (server is the only authority) ----------------
private data class Tier(val minDays: Int, val maxDays: Int, val perDayCents: Int, val viewsPerDay: Int)
private val TIERS = listOf(Tier(1, 2, 100, 1500), Tier(3, 6, 90, 1600), Tier(7, 13, 80, 1800), Tier(14, 29, 70, 2000), Tier(30, 59, 60, 2300), Tier(60, 90, 50, 2700))

@Serializable data class TierInfo(val minDays: Int, val maxDays: Int, val perDayCents: Int, val viewsPerDay: Int)
@Serializable data class Pricing(val currency: String, val tiers: List<TierInfo>, val minDays: Int = 1, val maxDays: Int = 90, val minAdSeconds: Int = MIN_AD_SECONDS)
@Serializable data class Quote(val days: Int, val priceCents: Int, val estimatedViews: Int, val perDayCents: Int, val viewsPerDay: Int, val currency: String)

fun pricing() = Pricing(Paystack.currency, TIERS.map { TierInfo(it.minDays, it.maxDays, it.perDayCents, it.viewsPerDay) })
fun quote(days: Int): Quote {
    val d = days.coerceIn(1, 90); val t = TIERS.first { d in it.minDays..it.maxDays }
    return Quote(d, d * t.perDayCents, d * t.viewsPerDay, t.perDayCents, t.viewsPerDay, Paystack.currency)
}

// ---------------- model ----------------
/** status: draft | awaiting_payment | active | paused | expired | completed */
@Serializable data class Ad(
    val id: String, val ownerSub: String, val ownerEmail: String, val title: String, val url: String, val description: String = "",
    val days: Int, val priceCents: Int, val currency: String, val targetViews: Int,
    val delivered: Int = 0, val impressions: Int = 0, val clicks: Int = 0,
    val status: String = "draft", val videoStatus: String = "none", val durationSec: Int = 0,
    val videoFile: String? = null, val thumbFile: String? = null, val refs: List<String> = emptyList(),
    val paidAt: Long = 0, val startsAt: Long = 0, val expiresAt: Long = 0, val createdAt: Long = 0, val error: String? = null
)
@Serializable data class AdPublic(val id: String, val title: String, val description: String, val url: String, val thumbnailUrl: String, val videoUrl: String, val durationSec: Int, val token: String)
@Serializable data class ViewReport(val adId: String, val token: String, val watchedSec: Int, val placement: String = "home")
@Serializable data class ViewResult(val counted: Boolean, val pass: String? = null)

class AdStore(root: File, private val scope: CoroutineScope) {
    val mediaDir = File(root, "ads").apply { mkdirs() }
    private val file = File(root, "ads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ads = ConcurrentHashMap<String, Ad>()

    init {
        runCatching { json.decodeFromString<List<Ad>>(file.readText()) }.getOrNull()?.forEach { ads[it.id] = it }
        scope.launch { while (isActive) { delay(60_000); sweep() } }
    }
    fun get(id: String) = ads[id]
    fun mine(sub: String) = ads.values.filter { it.ownerSub == sub }.sortedByDescending { it.createdAt }
    fun byRef(ref: String) = ads.values.firstOrNull { ref in it.refs }
    fun add(a: Ad) { ads[a.id] = a; save() }
    fun update(id: String, f: (Ad) -> Ad): Ad? { val r = ads.computeIfPresent(id) { _, v -> f(v) }; save(); return r }
    fun remove(id: String) { ads.remove(id); File(mediaDir, id).deleteRecursively(); save() }
    fun servable(a: Ad, now: Long = System.currentTimeMillis()) = a.status == "active" && now < a.expiresAt && a.delivered < a.targetViews && a.videoStatus == "ready"
    fun hasServable() = ads.values.any { servable(it) }
    fun allServable() = ads.values.filter { servable(it) }

    @Synchronized private fun save() = runCatching {
        val tmp = File(file.path + ".tmp"); tmp.writeText(json.encodeToString(ads.values.toList())); tmp.renameTo(file)
    }

    /** Expire on time, complete on delivery, clean up abandoned drafts. */
    private fun sweep() {
        val now = System.currentTimeMillis()
        ads.values.forEach { a ->
            when {
                (a.status == "active" || a.status == "paused") && now >= a.expiresAt -> update(a.id) { it.copy(status = "expired") }
                (a.status == "active" || a.status == "paused") && a.delivered >= a.targetViews -> update(a.id) { it.copy(status = "completed") }
                (a.status == "draft" || a.status == "awaiting_payment") && now - a.createdAt > 3 * DAY_MS -> remove(a.id)
            }
        }
    }
}

/** Global handle so the download route can enforce the ad gate. */
object AdsRuntime {
    lateinit var store: AdStore
    private val usedPasses = ConcurrentHashMap<String, Long>()
    fun init(root: File, scope: CoroutineScope) { store = AdStore(root, scope) }

    fun newPass(dev: String, nonce: String) = Auth.sign("pass|$dev|${System.currentTimeMillis() + 10 * 60_000}|$nonce")
    /** True when no ad is live (nothing to gate) or the client presents a valid, unused pass earned by watching an ad. */
    fun gateSatisfied(pass: String?, dev: String): Boolean {
        if (!store.hasServable()) return true
        val p = pass?.let(Auth::unsign)?.split('|') ?: return false
        if (p.size != 4 || p[0] != "pass" || p[1] != dev || (p[2].toLongOrNull() ?: 0) < System.currentTimeMillis()) return false
        usedPasses.entries.removeIf { it.value < System.currentTimeMillis() }
        return usedPasses.putIfAbsent(p[3], System.currentTimeMillis() + 15 * 60_000) == null
    }
}

// ---------------- ffmpeg helpers ----------------
suspend fun exec(cmd: List<String>, timeoutSec: Long): String? = withContext(Dispatchers.IO) {
    try {
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = async { p.inputStream.bufferedReader().use { it.readText() } }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); out.cancel(); null }
        else if (p.exitValue() == 0) out.await() else { out.cancel(); null }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}

/** Returns duration in seconds if the file has a video stream, else null. */
suspend fun probeDuration(f: File): Double? {
    val out = exec(listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_type:format=duration", "-of", "json", f.path), 30) ?: return null
    val o = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrNull() ?: return null
    if (o["streams"]?.jsonArray.isNullOrEmpty()) return null
    return o["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull
}

suspend fun transcodeAd(store: AdStore, id: String) {
    val dir = File(store.mediaDir, id); val raw = File(dir, "raw.tmp"); val mp4 = File(dir, "video.mp4"); val thumb = File(dir, "thumb.jpg")
    val ok = exec(listOf("ffmpeg", "-y", "-nostdin", "-loglevel", "error", "-i", raw.path, "-vf", "scale='min(1280,iw)':-2", "-c:v", "libx264", "-preset", "veryfast",
        "-crf", "26", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", mp4.path), 900) != null && mp4.isFile
    if (ok) exec(listOf("ffmpeg", "-y", "-nostdin", "-loglevel", "error", "-ss", "3", "-i", mp4.path, "-frames:v", "1", "-vf", "scale=480:-2", thumb.path), 60)
    raw.delete()
    store.update(id) { if (ok) it.copy(videoStatus = "ready", videoFile = "video.mp4", thumbFile = if (thumb.isFile) "thumb.jpg" else null, error = null) else it.copy(videoStatus = "failed", error = "video processing failed") }
}
