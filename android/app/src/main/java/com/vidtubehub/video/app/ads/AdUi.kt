package com.vidtubehub.video.app.ads

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vidtubehub.video.app.*
import com.vidtubehub.video.app.data.AdPublic
import com.vidtubehub.video.app.data.VideoRepository
import com.vidtubehub.video.app.player.PlayerCache
import com.vidtubehub.video.app.util.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun openAd(ctx: android.content.Context, ad: AdPublic) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    kotlinx.coroutines.GlobalScope.launch { VideoRepository.instance.reportClick(ad.id) }
}

/** Small "Sponsored" card that sits between videos in the feed. Tap = watch the ad (a billable view after 5s). */
@Composable fun SponsoredCard(ad: AdPublic, onWatch: () -> Unit) {
    val ctx = LocalContext.current; val repo = VideoRepository.instance
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Panel).clickable { onWatch() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Bg)) {
            AsyncImage(repo.absolute(ad.thumbnailUrl), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Sponsored"), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFC107)).padding(horizontal = 6.dp, vertical = 1.dp))
            Text(ad.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (ad.description.isNotBlank()) Text(ad.description, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("Learn more") + " ›", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { openAd(ctx, ad) }.padding(top = 4.dp))
        }
    }
}

/**
 * Full-screen ad player. placement: home | watch | download.
 *  - view is reported to the server after 5s of REAL playback (server verifies token + elapsed time)
 *  - download placement: "Continue" unlocks after 15s and returns a single-use pass from the server
 * onFinish(pass) is called when the user continues/skips/ad ends (pass only for download).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable fun AdOverlay(ad: AdPublic, placement: String, onFinish: (pass: String?) -> Unit) {
    val ctx = LocalContext.current; val repo = VideoRepository.instance; val scope = rememberCoroutineScope()
    val player = remember { PlayerCache.newPlayer(ctx) }
    val gate = placement == "download"
    val unlockAt = if (gate) 15 else 5
    var watched by remember { mutableIntStateOf(0) }; var pass by remember { mutableStateOf<String?>(null) }; var ended by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }; var failed by remember { mutableStateOf(false) }
    var reportedView by remember { mutableStateOf(false) }; var reportedPass by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { buffering = s == Player.STATE_BUFFERING; if (s == Player.STATE_ENDED) ended = true }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { failed = true }
        }
        player.addListener(l); player.setMediaItem(MediaItem.fromUri(repo.absolute(ad.videoUrl))); player.prepare(); player.playWhenReady = true
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) { var acc = 0L; while (isActive) { delay(250); if (player.isPlaying) { acc += 250; watched = (acc / 1000).toInt() } } }
    LaunchedEffect(watched) {
        if (watched >= 5 && !reportedView) { reportedView = true; scope.launch { repo.reportView(ad, watched, placement) } }
        if (gate && watched >= 15 && !reportedPass) { reportedPass = true; scope.launch { pass = repo.reportView(ad, watched, placement)?.pass } }
    }
    LaunchedEffect(failed) { if (failed) onFinish(null) } // never trap the user behind a broken ad
    val canContinue = watched >= unlockAt || ended
    LaunchedEffect(ended) { if (ended && gate) { delay(1200); onFinish(pass) } else if (ended) onFinish(null) }
    BackHandler(enabled = true) { if (canContinue) onFinish(pass) }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ c -> PlayerView(c).apply { useController = false; this.player = player; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, Modifier.fillMaxSize())
            if (buffering) CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.Center).size(36.dp))
            Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text(tr("Sponsored"), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFC107)).padding(horizontal = 8.dp, vertical = 2.dp))
                Text(ad.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp).background(Color.Black.copy(alpha = .5f)).padding(4.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .6f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ openAd(ctx, ad) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text(tr("Visit advertiser")) }
                Spacer(Modifier.weight(1f))
                Button({ onFinish(pass) }, enabled = canContinue, colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                    Text(when { canContinue && gate -> tr("Continue download"); canContinue -> tr("Skip ad"); else -> "${tr("Skip in")} ${unlockAt - watched}s" })
                }
            }
        }
    }
}
