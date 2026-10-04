package com.vidtubehub.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class GoogleLogin(val idToken: String)
@Serializable data class UserInfo(val id: String, val email: String, val name: String, val picture: String? = null)
@Serializable data class LoginResult(val token: String, val user: UserInfo)
@Serializable data class CreateAd(val title: String, val url: String, val description: String = "", val days: Int)
@Serializable data class PayInit(val authorizationUrl: String, val reference: String)

private class TokenState(val iat: Long, var counted: Boolean = false, var pass: Boolean = false)
private val tokens = ConcurrentHashMap<String, TokenState>()
private val lastCounted = ConcurrentHashMap<String, Long>()
private val publicBase = System.getenv("PUBLIC_BASE_URL")?.trimEnd('/') ?: "http://localhost:8080"
private val maxUploadBytes = (System.getenv("ADS_MAX_UPLOAD_MB")?.toLongOrNull() ?: 200L) * 1024 * 1024
private val ipHost = Regex("^[0-9.:\\[\\]]+$")

private fun validUrl(s: String) = s.length <= 500 && runCatching {
    val u = URI(s); val h = u.host?.lowercase()
    u.scheme == "https" && h != null && h.contains('.') && !ipHost.matches(h) && h != "localhost" && !h.endsWith(".local") && u.userInfo == null
}.getOrDefault(false)

private fun ApplicationCall.device() = request.headers["X-Device-Id"]?.take(64)?.takeIf { it.length >= 8 } ?: request.local.remoteAddress

/** Marks an ad active ONLY after Paystack confirms (server-to-server) the exact amount + currency for one of its references. */
suspend fun activateIfPaid(store: AdStore, adId: String): Ad? {
    val ad = store.get(adId) ?: return null
    if (ad.status != "awaiting_payment") return ad
    for (ref in ad.refs.reversed()) {
        if (Paystack.verify(ref, ad.priceCents, ad.currency)) {
            return store.update(adId) {
                if (it.status == "awaiting_payment") { val now = System.currentTimeMillis(); it.copy(status = "active", paidAt = now, startsAt = now, expiresAt = now + it.days * DAY_MS) } else it
            }
        }
    }
    return ad
}

fun Route.adRoutes(scope: CoroutineScope) {
    val store = AdsRuntime.store

    suspend fun ApplicationCall.owned(): Ad? {
        val u = user() ?: run { respondText("sign in required", status = HttpStatusCode.Unauthorized); return null }
        val ad = store.get(parameters["id"].orEmpty())
        if (ad == null || ad.ownerSub != u.sub) { respondText("not found", status = HttpStatusCode.NotFound); return null }
        return ad
    }

    // ---------- auth ----------
    post("/api/auth/google") {
        val s = Auth.verifyGoogle(call.receive<GoogleLogin>().idToken) ?: return@post call.respondText("invalid Google token", status = HttpStatusCode.Unauthorized)
        call.respond(LoginResult(Auth.issue(s), UserInfo(s.sub, s.email, s.name, s.picture)))
    }
    get("/api/me") {
        val s = call.user() ?: return@get call.respondText("sign in required", status = HttpStatusCode.Unauthorized)
        call.respond(UserInfo(s.sub, s.email, s.name, s.picture))
    }

    // ---------- pricing ----------
    get("/api/ads/pricing") { call.respond(pricing()) }
    get("/api/ads/quote") { call.respond(quote(call.request.queryParameters["days"]?.toIntOrNull() ?: 1)) }

    // ---------- advertiser ----------
    post("/api/ads") {
        val u = call.user() ?: return@post call.respondText("sign in required", status = HttpStatusCode.Unauthorized)
        val b = call.receive<CreateAd>()
        val title = b.title.trim(); val desc = b.description.trim()
        if (title.length !in 3..70) return@post call.respondText("Title must be 3-70 characters", status = HttpStatusCode.UnprocessableEntity)
        if (desc.length > 140) return@post call.respondText("Description max 140 characters", status = HttpStatusCode.UnprocessableEntity)
        if (!validUrl(b.url.trim())) return@post call.respondText("Enter a valid https:// link", status = HttpStatusCode.UnprocessableEntity)
        if (b.days !in 1..90) return@post call.respondText("Days must be 1-90", status = HttpStatusCode.UnprocessableEntity)
        if (store.mine(u.sub).count { it.status == "draft" || it.status == "awaiting_payment" } >= 10) return@post call.respondText("Too many unpaid drafts", status = HttpStatusCode.TooManyRequests)
        val q = quote(b.days) // price/views come from the server, never from the request
        val ad = Ad(UUID.randomUUID().toString().replace("-", "").take(16), u.sub, u.email, title, b.url.trim(), desc, q.days, q.priceCents, q.currency, q.estimatedViews, createdAt = System.currentTimeMillis())
        store.add(ad); call.respond(ad)
    }

    // raw-body upload (no multipart): PUT /api/ads/{id}/video
    put("/api/ads/{id}/video") {
        val ad = call.owned() ?: return@put
        if (ad.status != "draft" && ad.status != "awaiting_payment") return@put call.respondText("Ad can't be changed after payment", status = HttpStatusCode.Conflict)
        if ((call.request.contentLength() ?: 0L) > maxUploadBytes) return@put call.respondText("File too large (max ${maxUploadBytes / 1024 / 1024} MB)", status = HttpStatusCode.PayloadTooLarge)
        val dir = File(store.mediaDir, ad.id).apply { mkdirs() }; val raw = File(dir, "raw.tmp")
        var total = 0L; var tooBig = false
        withContext(Dispatchers.IO) {
            call.receiveStream().use { inp -> raw.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > maxUploadBytes) { tooBig = true; break }; out.write(buf, 0, n) }
            } }
        }
        if (tooBig) { raw.delete(); return@put call.respondText("File too large (max ${maxUploadBytes / 1024 / 1024} MB)", status = HttpStatusCode.PayloadTooLarge) }
        val dur = probeDuration(raw)
        val err = when {
            dur == null -> "That file isn't a valid video"
            dur < MIN_AD_SECONDS -> "Ad video must be at least $MIN_AD_SECONDS seconds (yours is ${dur.toInt()}s)"
            dur > MAX_AD_SECONDS -> "Ad video must be at most $MAX_AD_SECONDS seconds (yours is ${dur.toInt()}s)"
            else -> null
        }
        if (err != null) { raw.delete(); return@put call.respondText(err, status = HttpStatusCode.UnprocessableEntity) } // server-side 30s check
        val updated = store.update(ad.id) { it.copy(videoStatus = "processing", durationSec = dur!!.toInt(), error = null) }!!
        scope.launch { transcodeAd(store, ad.id) }
        call.respond(updated)
    }

    post("/api/ads/{id}/pay") {
        val ad = call.owned() ?: return@post
        if (!Paystack.enabled) return@post call.respondText("Payments not configured", status = HttpStatusCode.ServiceUnavailable)
        if (ad.status != "draft" && ad.status != "awaiting_payment") return@post call.respondText("Already paid", status = HttpStatusCode.Conflict)
        if (ad.videoStatus != "ready") return@post call.respondText("Ad video is not ready yet", status = HttpStatusCode.Conflict)
        val q = quote(ad.days); val ref = "ad_${ad.id}_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        store.update(ad.id) { it.copy(status = "awaiting_payment", priceCents = q.priceCents, targetViews = q.estimatedViews, currency = q.currency, refs = it.refs + ref) }
        val url = Paystack.initialize(ad.ownerEmail, q.priceCents, ref, "$publicBase/pay/return", ad.id)
            ?: return@post call.respondText("Could not start payment", status = HttpStatusCode.BadGateway)
        call.respond(PayInit(url, ref))
    }
    post("/api/ads/{id}/verify") { val ad = call.owned() ?: return@post; call.respond(activateIfPaid(store, ad.id) ?: ad) }

    get("/api/ads/mine") {
        val u = call.user() ?: return@get call.respondText("sign in required", status = HttpStatusCode.Unauthorized)
        call.respond(store.mine(u.sub))
    }
    post("/api/ads/{id}/pause") { val ad = call.owned() ?: return@post; call.respond(store.update(ad.id) { if (it.status == "active") it.copy(status = "paused") else it }!!) }
    post("/api/ads/{id}/resume") { val ad = call.owned() ?: return@post; call.respond(store.update(ad.id) { if (it.status == "paused" && System.currentTimeMillis() < it.expiresAt) it.copy(status = "active") else it }!!) }
    delete("/api/ads/{id}") {
        val ad = call.owned() ?: return@delete
        if (ad.status != "draft" && ad.status != "awaiting_payment") return@delete call.respondText("Paid campaigns can't be deleted", status = HttpStatusCode.Conflict)
        store.remove(ad.id); call.respond(HttpStatusCode.NoContent)
    }

    // ---------- Paystack ----------
    post("/api/paystack/webhook") {
        val raw = call.receiveText()
        if (!Paystack.validSignature(raw, call.request.headers["x-paystack-signature"])) return@post call.respondText("bad signature", status = HttpStatusCode.Unauthorized)
        runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            if (o["event"]?.jsonPrimitive?.contentOrNull == "charge.success") {
                val ref = o["data"]?.jsonObject?.get("reference")?.jsonPrimitive?.contentOrNull
                store.byRef(ref.orEmpty())?.let { activateIfPaid(store, it.id) } // re-verified with Paystack, webhook body isn't trusted either
            }
        }
        call.respondText("ok")
    }
    get("/pay/return") {
        val ref = call.request.queryParameters["reference"] ?: call.request.queryParameters["trxref"]
        val ad = ref?.let { store.byRef(it) }?.let { activateIfPaid(store, it.id) }
        val msg = if (ad?.status == "active") "Payment confirmed. Your campaign is live — return to the app." else "We're confirming your payment. Return to the app; it will update shortly."
        call.respondText("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;background:#070c11;color:#fff;text-align:center;padding:48px 20px'><h2>VidTube Hub</h2><p>$msg</p>", ContentType.Text.Html)
    }

    // ---------- delivery ----------
    get("/api/ads/serve") {
        val n = (call.request.queryParameters["n"]?.toIntOrNull() ?: 1).coerceIn(1, 6)
        val placement = (call.request.queryParameters["placement"] ?: "home").take(12)
        val dev = call.device(); val now = System.currentTimeMillis()
        val pool = store.allServable().filter { a -> // pacing: spread delivery over the campaign instead of burning it in an hour
            val el = ((now - a.startsAt).toDouble() / (a.expiresAt - a.startsAt).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            a.delivered < a.targetViews * minOf(1.0, el * 1.5 + 0.05) + 5
        }.toMutableList()
        val out = mutableListOf<AdPublic>()
        while (out.size < n && pool.isNotEmpty()) {
            val total = pool.sumOf { (it.targetViews - it.delivered).coerceAtLeast(1) }.toDouble()
            var r = Math.random() * total; val pick = pool.firstOrNull { r -= (it.targetViews - it.delivered).coerceAtLeast(1); r <= 0 } ?: pool.last()
            pool.remove(pick)
            val nonce = UUID.randomUUID().toString(); val iat = now
            tokens[nonce] = TokenState(iat)
            store.update(pick.id) { it.copy(impressions = it.impressions + 1) }
            out += AdPublic(pick.id, pick.title, pick.description, pick.url, "/api/ads/media/${pick.id}/thumb.jpg", "/api/ads/media/${pick.id}/video.mp4", pick.durationSec,
                Auth.sign("${pick.id}|$dev|$iat|$nonce|$placement"))
        }
        if (tokens.size > 50_000) tokens.entries.removeIf { it.value.iat < now - 2 * 3_600_000 }
        call.respond(out)
    }

    /** A "view" = the ad video actually played >= 5s, proven by a signed single-ad token and real elapsed time. */
    post("/api/ads/view") {
        val b = call.receive<ViewReport>(); val dev = call.device(); val now = System.currentTimeMillis()
        val p = Auth.unsign(b.token)?.split('|')
        if (p == null || p.size != 5 || p[0] != b.adId || p[1] != dev) return@post call.respondText("bad token", status = HttpStatusCode.Forbidden)
        val st = tokens[p[3]] ?: return@post call.respondText("unknown token", status = HttpStatusCode.Forbidden)
        val iat = p[2].toLongOrNull() ?: 0; val elapsed = (now - iat) / 1000
        if (elapsed > 2 * 3600 || b.watchedSec > elapsed + 1) return@post call.respondText("implausible watch time", status = HttpStatusCode.Forbidden)
        val ad = store.get(b.adId); var counted = false; var pass: String? = null
        synchronized(st) {
            if (ad != null && b.watchedSec >= 5 && !st.counted && store.servable(ad) && call.user()?.sub != ad.ownerSub) {
                val k = "$dev|${ad.id}"; val last = lastCounted[k] ?: 0L
                if (now - last >= 30 * 60_000) { // one billable view per device per ad per 30 min
                    lastCounted[k] = now; st.counted = true; counted = true
                    store.update(ad.id) { val d = it.delivered + 1; it.copy(delivered = d, status = if (d >= it.targetViews) "completed" else it.status) }
                }
            }
            if (p[4] == "download" && b.placement == "download" && b.watchedSec >= 15 && !st.pass) { st.pass = true; pass = AdsRuntime.newPass(dev, p[3]) }
        }
        if (lastCounted.size > 100_000) lastCounted.entries.removeIf { it.value < now - 3_600_000 }
        call.respond(ViewResult(counted, pass))
    }
    post("/api/ads/{id}/click") { store.update(call.parameters["id"].orEmpty()) { it.copy(clicks = it.clicks + 1) }; call.respond(HttpStatusCode.NoContent) }

    get("/api/ads/media/{id}/{file}") {
        val ad = store.get(call.parameters["id"].orEmpty()); val name = call.parameters["file"]
        if (ad == null || (ad.status != "active" && ad.status != "paused") || (name != "video.mp4" && name != "thumb.jpg")) return@get call.respondText("not found", status = HttpStatusCode.NotFound)
        val f = File(File(store.mediaDir, ad.id), name)
        if (!f.isFile) return@get call.respondText("not found", status = HttpStatusCode.NotFound)
        call.respond(LocalFileContent(f, if (name == "video.mp4") ContentType.Video.MP4 else ContentType.Image.JPEG))
    }
}
