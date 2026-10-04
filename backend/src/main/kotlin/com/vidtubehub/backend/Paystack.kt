package com.vidtubehub.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Env: PAYSTACK_SECRET_KEY, PAYSTACK_CURRENCY (default USD - must be enabled on your Paystack account). Amounts are in the currency's subunit (cents). */
object Paystack {
    private val secret = System.getenv("PAYSTACK_SECRET_KEY").orEmpty()
    val currency: String = System.getenv("PAYSTACK_CURRENCY")?.uppercase() ?: "USD"
    val enabled get() = secret.isNotBlank()
    private val json = Json { ignoreUnknownKeys = true }

    private fun builder(path: String) = HttpRequest.newBuilder(URI("https://api.paystack.co$path")).header("Authorization", "Bearer $secret")

    suspend fun initialize(email: String, amount: Int, ref: String, callback: String, adId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("email", email); put("amount", amount); put("currency", currency); put("reference", ref); put("callback_url", callback)
                put("metadata", buildJsonObject { put("adId", adId) })
            }.toString()
            val r = netHttp.send(builder("/transaction/initialize").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            json.parseToJsonElement(r.body()).jsonObject["data"]?.jsonObject?.get("authorization_url")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    /** Server-to-server check: never activate an ad from anything the client says. */
    suspend fun verify(ref: String, expectedAmount: Int, expectedCurrency: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val r = netHttp.send(builder("/transaction/verify/${java.net.URLEncoder.encode(ref, "UTF-8")}").GET().build(), HttpResponse.BodyHandlers.ofString())
            val root = json.parseToJsonElement(r.body()).jsonObject
            val d = root["data"]?.jsonObject ?: return@runCatching false
            root["status"]?.jsonPrimitive?.booleanOrNull == true &&
                d["status"]?.jsonPrimitive?.contentOrNull == "success" &&
                d["reference"]?.jsonPrimitive?.contentOrNull == ref &&
                d["amount"]?.jsonPrimitive?.longOrNull == expectedAmount.toLong() &&
                d["currency"]?.jsonPrimitive?.contentOrNull.equals(expectedCurrency, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun validSignature(rawBody: String, header: String?): Boolean {
        if (!enabled || header.isNullOrBlank()) return false
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA512")) }
        val expected = mac.doFinal(rawBody.toByteArray()).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), header.lowercase().toByteArray())
    }
}
