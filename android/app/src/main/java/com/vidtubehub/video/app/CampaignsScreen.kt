package com.vidtubehub.video.app

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.vidtubehub.video.app.auth.Account
import com.vidtubehub.video.app.data.*
import com.vidtubehub.video.app.util.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private fun money(cents: Int, cur: String) = if (cur == "USD") "$%.2f".format(cents / 100.0) else "%.2f %s".format(cents / 100.0, cur)
private fun quoteLocal(p: Pricing, days: Int): Triple<Int, Int, TierInfo> { val t = p.tiers.first { days in it.minDays..it.maxDays }; return Triple(days * t.perDayCents, days * t.viewsPerDay, t) }
private fun fmt(n: Int) = NumberFormat.getIntegerInstance(Locale.US).format(n)

@Composable fun CampaignsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current; val act = ctx as Activity; val repo = VideoRepository.instance; val scope = rememberCoroutineScope()
    val user = Account.user
    var pricing by remember { mutableStateOf<Pricing?>(null) }; var ads by remember { mutableStateOf<List<Ad>>(emptyList()) }
    var msg by remember { mutableStateOf("") }; var busy by remember { mutableStateOf("") }; var progress by remember { mutableFloatStateOf(0f) }
    var title by remember { mutableStateOf("") }; var link by remember { mutableStateOf("https://") }; var desc by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(1) }; var videoUri by remember { mutableStateOf<Uri?>(null) }; var videoSec by remember { mutableIntStateOf(0) }
    BackHandler { onBack() }

    suspend fun refresh() {
        runCatching { pricing = pricing ?: repo.pricing(); if (Account.user != null) ads = repo.myAds() }
            .onFailure { if ((it as? ApiException)?.code == 401) Account.clear() else msg = it.message ?: "Network error" }
    }
    LaunchedEffect(user?.id) { refresh() }
    // Coming back from the Paystack page: ask the SERVER to verify each unpaid campaign with Paystack.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { ads.filter { it.status == "awaiting_payment" }.forEach { runCatching { repo.verifyAd(it.id) } }; refresh() }
    }
    LaunchedEffect(ads.any { it.status == "awaiting_payment" }) { // poll while a payment is pending (webhook may land first)
        var n = 0; while (ads.any { it.status == "awaiting_payment" } && n++ < 100) { delay(4000); ads.filter { it.status == "awaiting_payment" }.forEach { runCatching { repo.verifyAd(it.id) } }; refresh() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val sec = runCatching { MediaMetadataRetriever().run { setDataSource(ctx, uri); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); (d / 1000).toInt() } }.getOrDefault(0)
            if (sec < 30) { msg = "${tr("Ad video must be at least 30 seconds")} (${sec}s)"; videoUri = null } else { msg = ""; videoUri = uri; videoSec = sec }
        }
    }
    suspend fun openPayment(ad: Ad) {
        val init = repo.payAd(ad.id)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(init.authorizationUrl)))
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Fg, fontSize = 32.sp, modifier = Modifier.clickableNoRipple { onBack() }.padding(end = 12.dp))
            Text(tr("Campaigns"), color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (user == null) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Reach more users instantly"), color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(tr("Sign in with Google to create and pay for a campaign."), color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                Button({ scope.launch {
                    busy = "signin"; msg = ""
                    try { Account.signIn(act) }
                    catch (e: CancellationException) { throw e }
                    catch (e: GetCredentialCancellationException) { msg = tr("Google sign-in did not finish. If you selected an account, check Android OAuth package/SHA-1 setup.") }
                    catch (e: NoCredentialException) { msg = tr("No Google account is available on this device. Add one in Android Settings and try again.") }
                    catch (e: ApiException) { msg = if (e.code == 401) tr("Google token rejected by backend (HTTP 401). Check OAuth client configuration.") else "Google sign-in failed (HTTP ${e.code})." }
                    catch (e: GetCredentialException) { msg = "Google sign-in failed (${e.javaClass.simpleName}). Check Google Play services and OAuth setup." }
                    catch (e: Exception) { msg = e.message ?: tr("Google sign-in failed.") }
                    finally { busy = "" }
                } }, enabled=busy.isEmpty(), colors=ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Text(tr("Sign in with Google"))
                }
                if (msg.isNotBlank()) Text(msg, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                pricing?.let { PricingTable(it) }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    user.picture?.let { AsyncImage(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(user.name, color = Fg, fontWeight = FontWeight.Bold); Text(user.email, color = Muted, fontSize = 12.sp) }
                    TextButton({ scope.launch { Account.signOut(ctx); ads = emptyList() } }) { Text(tr("Sign out"), color = Red) }
                }
            }
            pricing?.let { p ->
                item { PricingTable(p) }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp)) {
                        Text(tr("Create ad"), color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(title, { title = it.take(70) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text(tr("Ad title")) })
                        OutlinedTextField(link, { link = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Link users will open (https://)")) })
                        OutlinedTextField(desc, { desc = it.take(140) }, Modifier.fillMaxWidth(), label = { Text(tr("Short description (optional)")) }, maxLines = 2)
                        val (cents, views, tier) = quoteLocal(p, days)
                        Text("${tr("Run for")} $days ${if (days == 1) tr("day") else tr("days")} (${tr("max")})", color = Fg, modifier = Modifier.padding(top = 12.dp))
                        Slider(days.toFloat(), { days = it.toInt() }, valueRange = p.minDays.toFloat()..p.maxDays.toFloat(), steps = p.maxDays - p.minDays - 1)
                        Text("${money(cents, p.currency)}  ·  ≈ ${fmt(views)} ${tr("views")}", color = Red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${money(tier.perDayCents, p.currency)}/${tr("day")} · ${fmt(tier.viewsPerDay)} ${tr("views")}/${tr("day")}. ${tr("Stops when views are delivered or the days end — whichever is first. Unused views aren't refunded.")}", color = Muted, fontSize = 12.sp)
                        OutlinedButton({ picker.launch("video/*") }, Modifier.padding(top = 10.dp)) { Text(if (videoUri == null) tr("Choose ad video (min 30 s)") else "✓ ${videoSec}s ${tr("selected — tap to change")}") }
                        val valid = title.trim().length >= 3 && link.trim().startsWith("https://") && link.trim().length > 12 && videoUri != null && busy.isEmpty()
                        Button({
                            scope.launch {
                                try {
                                    busy = tr("Creating…"); val ad = repo.createAd(CreateAdReq(title.trim(), link.trim(), desc.trim(), days))
                                    busy = tr("Uploading…"); progress = 0f; repo.uploadAdVideo(ad.id, videoUri!!) { progress = it }
                                    busy = tr("Processing video…"); progress = 1f
                                    var tries = 0; while (tries++ < 120) { refresh(); val cur = ads.firstOrNull { it.id == ad.id }; if (cur?.videoStatus == "ready") break; if (cur?.videoStatus == "failed") error(cur.error ?: "Video processing failed"); delay(2000) }
                                    busy = tr("Opening Paystack…"); openPayment(ad)
                                    title = ""; link = "https://"; desc = ""; videoUri = null; msg = tr("Complete the payment in your browser, then return here.")
                                } catch (e: Exception) { msg = e.message ?: "Error"; if ((e as? ApiException)?.code == 401) Account.clear() }
                                busy = ""; refresh()
                            }
                        }, enabled = valid, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                            Text(if (busy.isEmpty()) "${tr("Pay")} ${money(cents, p.currency)} ${tr("with Paystack")}" else busy)
                        }
                        if (busy == tr("Uploading…")) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 8.dp), color = Red)
                        if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("Complete") || msg.startsWith(tr("Complete"))) Muted else Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    } }
                }
            }
            item { Text(tr("My campaigns"), color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (ads.isEmpty()) item { Text(tr("No campaigns yet."), color = Muted) }
            items(ads, key = { it.id }) { ad -> CampaignCard(ad,
                onPay = { scope.launch { runCatching { openPayment(ad) }.onFailure { msg = it.message ?: "Error" } } },
                onAction = { a -> scope.launch { runCatching { if (a == "delete") repo.deleteAd(ad.id) else repo.adAction(ad.id, a) }.onFailure { msg = it.message ?: "Error" }; refresh() } }) }
        }
    }
}

@Composable private fun PricingTable(p: Pricing) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Column(Modifier.padding(16.dp)) {
        Text(tr("Pricing"), color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("Start from 1 day for $1 — 1,500 views. Longer campaigns get a lower daily price and more views."), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        p.tiers.forEach { t ->
            val range = if (t.minDays == t.maxDays) "${t.minDays}" else "${t.minDays}–${t.maxDays}"
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("$range ${tr("days")}", color = Fg, modifier = Modifier.weight(1f)); Text("${money(t.perDayCents, p.currency)}/${tr("day")}", color = Fg, modifier = Modifier.weight(1f)); Text("${fmt(t.viewsPerDay)} ${tr("views")}/${tr("day")}", color = Muted, modifier = Modifier.weight(1.3f), fontSize = 13.sp)
            }
        }
        Text(tr("A view = your ad video played for at least 5 seconds."), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    } }
}

@Composable private fun CampaignCard(ad: Ad, onPay: () -> Unit, onAction: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Row(Modifier.padding(12.dp)) {
        if (ad.thumbFile != null) AsyncImage(VideoRepository.instance.absolute("/api/ads/media/${ad.id}/thumb.jpg"), null, Modifier.width(90.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(start = if (ad.thumbFile != null) 10.dp else 0.dp)) {
            Text(ad.title, color = Fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val color = when (ad.status) { "active" -> Color(0xFF2E7D32); "paused" -> Color(0xFFF9A825); "awaiting_payment", "draft" -> Muted; else -> Red }
            Text(ad.status.replace('_', ' ').uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { if (ad.targetViews > 0) (ad.delivered.toFloat() / ad.targetViews).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Red)
            Text("${fmt(ad.delivered)} / ${fmt(ad.targetViews)} ${tr("views")} · ${ad.clicks} ${tr("clicks")} · ${money(ad.priceCents, ad.currency)}", color = Muted, fontSize = 12.sp)
            if (ad.status == "active" || ad.status == "paused") {
                val left = ((ad.expiresAt - System.currentTimeMillis()) / 3_600_000).coerceAtLeast(0)
                Text("${tr("Expires in")} ${if (left >= 48) "${left / 24}d" else "${left}h"}", color = Muted, fontSize = 12.sp)
            }
            Row {
                if (ad.status == "draft" || ad.status == "awaiting_payment") { TextButton(onPay, contentPadding = PaddingValues(0.dp)) { Text(tr("Complete payment"), color = Red, fontSize = 13.sp) }; Spacer(Modifier.width(12.dp)); TextButton({ onAction("delete") }, contentPadding = PaddingValues(0.dp)) { Text(tr("Delete"), color = Muted, fontSize = 13.sp) } }
                if (ad.status == "active") TextButton({ onAction("pause") }, contentPadding = PaddingValues(0.dp)) { Text(tr("Pause"), color = Red, fontSize = 13.sp) }
                if (ad.status == "paused") TextButton({ onAction("resume") }, contentPadding = PaddingValues(0.dp)) { Text(tr("Resume"), color = Red, fontSize = 13.sp) }
            }
        }
    } }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))
