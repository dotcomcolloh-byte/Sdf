package com.vidtubehub.backend

import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class Session(val sub: String, val email: String, val name: String, val picture: String? = null, val exp: Long)

val netHttp: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

/**
 * Env:
 *  GOOGLE_CLIENT_IDS  comma separated OAuth client IDs accepted as the ID token audience (the WEB client id used as serverClientId in the app)
 *  SESSION_SECRET     >= 16 chars, signs our own session tokens + view/pass tokens
 */
object Auth {
    private val json = Json { ignoreUnknownKeys = true }
    private val secret: ByteArray = (System.getenv("SESSION_SECRET")?.takeIf { it.length >= 16 }
        ?: run { System.err.println("WARN: SESSION_SECRET not set; using a random one (sessions reset on restart)"); UUID.randomUUID().toString() + UUID.randomUUID() }).toByteArray()
    private val clientIds = System.getenv("GOOGLE_CLIENT_IDS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun ub64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    private fun hmac(data: String): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(secret, "HmacSHA256")); doFinal(data.toByteArray()) }

    /** payload -> "b64(payload).b64(hmac)" ; unsign returns the payload only if the signature is valid. */
    fun sign(payload: String): String { val p = b64(payload.toByteArray()); return "$p.${b64(hmac(p))}" }
    fun unsign(tok: String): String? {
        val i = tok.indexOf('.'); if (i <= 0) return null
        val sig = runCatching { ub64(tok.substring(i + 1)) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(sig, hmac(tok.substring(0, i)))) return null
        return runCatching { String(ub64(tok.substring(0, i))) }.getOrNull()
    }

    fun issue(s: Session): String = sign(json.encodeToString(s))
    fun parse(tok: String): Session? =
        unsign(tok)?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }?.takeIf { it.exp > System.currentTimeMillis() / 1000 }

    // ---------- Google ID token verification (RS256 against Google's published keys) ----------
    private var keys: Pair<Long, Map<String, PublicKey>>? = null
    private fun fetchKeys(): Map<String, PublicKey> {
        val r = netHttp.send(HttpRequest.newBuilder(URI("https://www.googleapis.com/oauth2/v3/certs")).GET().build(), HttpResponse.BodyHandlers.ofString())
        val kf = KeyFactory.getInstance("RSA")
        return json.parseToJsonElement(r.body()).jsonObject["keys"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["kid"]!!.jsonPrimitive.content to kf.generatePublic(RSAPublicKeySpec(BigInteger(1, ub64(o["n"]!!.jsonPrimitive.content)), BigInteger(1, ub64(o["e"]!!.jsonPrimitive.content))))
        }
    }
    @Synchronized private fun key(kid: String): PublicKey? {
        val now = System.currentTimeMillis(); var k = keys
        if (k == null || now - k.first > 3_600_000 || (kid !in k.second && now - k.first > 60_000)) { k = now to fetchKeys(); keys = k }
        return k.second[kid]
    }

    suspend fun verifyGoogle(idToken: String): Session? = withContext(Dispatchers.IO) {
        runCatching {
            if (clientIds.isEmpty()) error("GOOGLE_CLIENT_IDS not configured")
            val parts = idToken.split('.'); require(parts.size == 3)
            val header = json.parseToJsonElement(String(ub64(parts[0]))).jsonObject
            require(header["alg"]?.jsonPrimitive?.content == "RS256")
            val pub = key(header["kid"]!!.jsonPrimitive.content) ?: error("unknown key")
            val ok = Signature.getInstance("SHA256withRSA").run { initVerify(pub); update("${parts[0]}.${parts[1]}".toByteArray()); verify(ub64(parts[2])) }
            require(ok) { "bad signature" }
            val c = json.parseToJsonElement(String(ub64(parts[1]))).jsonObject
            fun s(k: String) = c[k]?.jsonPrimitive?.contentOrNull
            require(s("iss") == "accounts.google.com" || s("iss") == "https://accounts.google.com")
            require(s("aud") in clientIds) { "audience mismatch" }
            require((c["exp"]?.jsonPrimitive?.longOrNull ?: 0) > System.currentTimeMillis() / 1000) { "expired" }
            require(s("email_verified") == "true") { "email not verified" }
            Session(s("sub")!!, s("email")!!, s("name") ?: s("email")!!.substringBefore('@'), s("picture"), System.currentTimeMillis() / 1000 + 30L * 86400)
        }.onFailure { System.err.println("Google verify failed: ${it.message}") }.getOrNull()
    }
}

fun ApplicationCall.user(): Session? =
    request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }?.let(Auth::parse)
