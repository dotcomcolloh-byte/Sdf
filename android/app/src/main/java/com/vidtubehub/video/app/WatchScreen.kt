package com.vidtubehub.video.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vidtubehub.video.app.data.*
import com.vidtubehub.video.app.ads.*
import com.vidtubehub.video.app.download.DownloadStore
import com.vidtubehub.video.app.download.Downloader
import com.vidtubehub.video.app.player.PlayerCache
import com.vidtubehub.video.app.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@OptIn(UnstableApi::class)
@Composable fun WatchScreen(req: PlayRequest, onBack: () -> Unit, onVideo: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current; val repo = VideoRepository.instance; val scope = rememberCoroutineScope()
    val video = req.video
    val online by remember { ctx.onlineFlow() }.collectAsState(initial = ctx.isOnline())
    val player = remember { PlayerCache.newPlayer(ctx) }
    var quality by remember(video.id) { mutableIntStateOf(Settings.playQuality) } // 0 = auto
    var status by remember(video.id) { mutableStateOf(tr("Loading…")) }
    var playbackFailed by remember(video.id) { mutableStateOf(false) }
    var retryNonce by remember(video.id) { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }; var buffering by remember { mutableStateOf(true) }
    var posMs by remember { mutableLongStateOf(0L) }; var durMs by remember { mutableLongStateOf(0L) }; var bufMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }; var controls by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }; var showDownload by remember { mutableStateOf(false) }
    var related by remember(video.id) { mutableStateOf<List<Video>>(emptyList()) }; var relPage by remember(video.id) { mutableIntStateOf(-1) }
    var relMore by remember(video.id) { mutableStateOf(true) }; var relBusy by remember { mutableStateOf(false) }
    var formats by remember(video.id) { mutableStateOf<Formats?>(null) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var adBreak by remember { mutableStateOf<AdPublic?>(null) }; val firedBreaks = remember(video.id) { mutableSetOf<Int>() }
    val activity = ctx as? Activity
    val fallbackDur = remember(video.id) { parseDuration(video.duration) } // HLS is "live-style" until ffmpeg finishes, so player.duration may be unknown
    LaunchedEffect(video.id) { HistoryStore.record(video) }
    // Sponsored breaks (only when a paid ad exists): long videos get >= 2 (mid + almost-end), very long 3, mid-length 1 near the end.
    val breakPoints = remember(video.id) {
        val d = fallbackDur; val endBreak = d - 20_000
        when { req.localPath != null || d <= 0 -> emptyList()
            d >= 40 * 60_000 -> listOf(d / 4, d * 6 / 10, endBreak)
            d >= 10 * 60_000 -> listOf(d / 2, endBreak)
            d >= 2 * 60_000 -> listOf(endBreak)
            else -> emptyList() }
    }

    // ---- player events + automatic recovery after connection loss ----
    DisposableEffect(player) {
        var retries = 0
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING; if (s == Player.STATE_READY) { retries = 0; status = ""; playbackFailed = false }
                if (s == Player.STATE_ENDED && Settings.autoplay) related.firstOrNull()?.let { onVideo(PlayRequest(it)) }
            }
            override fun onPlayerError(e: PlaybackException) {
                val net = e.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                if (net && retries++ < 8) scope.launch {
                    status = tr("Connection lost — resuming when back online…")
                    if (!ctx.isOnline()) ctx.onlineFlow().first { it } else delay(2000L * retries)
                    player.prepare(); player.playWhenReady = true // continues from the current position, cached ranges reused
                } else { buffering = false; playbackFailed = true; status = "${tr("Playback error")}: ${e.errorCodeName}" }
            }
        }
        player.addListener(l)
        onDispose { HistoryStore.progress(video.id, player.currentPosition, if (player.duration > 0) player.duration else fallbackDur); player.removeListener(l); player.release(); PlayerScreenFlags.restore(activity) }
    }

    // ---- resolve source: offline file > server cache > instant proxy; waits/polls if server is still preparing ----
    LaunchedEffect(video.id, quality, req.localPath, retryNonce) {
        val resume = if (loadedFor == video.id) player.currentPosition else req.startMs
        playbackFailed = false; buffering = true; status = tr("Loading…")
        try {
            val local = req.localPath ?: DownloadStore.items.value.firstOrNull {
                it.id == video.id && it.kind == "video" && it.status == "done" && it.path?.let { p -> File(p).exists() } == true && (quality == 0 || it.quality == quality)
            }?.path
            if (local != null) {
                val src = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx)).createMediaSource(MediaItem.fromUri(Uri.fromFile(File(local))))
                player.setMediaSource(src, resume)
            } else {
                val s = retryWhenOnline(ctx, onWait = { status = tr("Waiting for connection…") }) {
                    var stream = repo.stream(video.id, quality, video.title)
                    val startedAt = System.currentTimeMillis()
                    while (stream.url == null) {
                        if (System.currentTimeMillis() - startedAt > 120_000) throw IOException("Video preparation timed out")
                        status = "${tr("Preparing")} ${stream.quality}p…"; delay(1000); stream = repo.stream(video.id, quality, video.title)
                    }
                    stream
                }
                val url = repo.absolute(s.url!!)
                if (s.source == "hls") player.setMediaSource(PlayerCache.hlsSource(ctx, url), resume) // chunked: starts after first segments
                else player.setMediaItem(MediaItem.fromUri(url), resume)
            }
            loadedFor = video.id; player.prepare(); player.playWhenReady = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            buffering = false; playbackFailed = true
            val http = Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.getOrNull(1)
            status = if (http != null) "${tr("Playback error")} (HTTP $http)" else tr("Playback error")
        }
    }

    // ---- related list + infinite paging ----
    fun loadRelated() {
        if (relBusy || !relMore || req.localPath != null) return
        relBusy = true
        scope.launch {
            runCatching { repo.videos(video.channel, relPage + 1) }.onSuccess { r ->
                val fresh = r.value.items.filter { n -> n.id != video.id && related.none { it.id == n.id } }
                related = related + fresh; relPage = r.value.page; relMore = r.value.hasMore && !r.stale
            }.onFailure { relMore = false }
            relBusy = false
        }
    }
    LaunchedEffect(video.id) { loadRelated() }
    val relList = rememberLazyListState()
    InfiniteEffect(relList, related.size) { loadRelated() }

    // ---- pre-play ahead: warm the next video's first bytes (skipped on metered data when Wi-Fi-only) ----
    LaunchedEffect(related.firstOrNull()?.id, online) {
        val next = related.firstOrNull() ?: return@LaunchedEffect
        if (!online || (Settings.wifiOnly && ctx.isMetered())) return@LaunchedEffect
        delay(4000) // let the current video buffer first
        val s = runCatching { repo.stream(next.id, 0, next.title) }.getOrNull() ?: return@LaunchedEffect
        val u = repo.absolute(s.url ?: return@LaunchedEffect)
        if (s.source == "hls") { val b = u.substringBeforeLast('/'); listOf("init.mp4", "seg_00000.m4s", "seg_00001.m4s").forEach { PlayerCache.prefetch(ctx, "$b/$it") } }
        else PlayerCache.prefetch(ctx, u, 4_000_000)
    }

    LaunchedEffect(player) { var n = 0; while (isActive) {
        if (!dragging) posMs = player.currentPosition
        durMs = if (player.duration > 0 && !player.isCurrentMediaItemLive) player.duration else maxOf(fallbackDur, player.duration.coerceAtLeast(0)); bufMs = player.bufferedPosition
        if (++n % 20 == 0 && posMs > 0) HistoryStore.progress(video.id, posMs, durMs)
        if (adBreak == null && player.isPlaying) breakPoints.forEachIndexed { i, at ->
            if (i !in firedBreaks && posMs >= at && posMs < at + 15_000) {
                firedBreaks += i
                scope.launch { repo.serveAds("watch", 1).firstOrNull()?.let { player.pause(); adBreak = it } }
            }
        }
        delay(250) } }
    LaunchedEffect(controls, playing, posMs / 4000) { if (controls && playing && !menu) { delay(3500); controls = false } }
    BackHandler { if (fullscreen) { fullscreen = false; PlayerScreenFlags.apply(activity, false) } else onBack() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Box((if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)).background(Color.Black).clickable { controls = !controls }) {
            AndroidView({ c -> PlayerView(c).apply { useController = false; this.player = player; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, Modifier.fillMaxSize())
            if (req.audioOnly) AsyncImage(video.thumbnailUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .5f)
            if (buffering || status.isNotBlank()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (buffering) CircularProgressIndicator(color = Red, modifier = Modifier.size(36.dp))
                if (status.isNotBlank()) Text(status, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).background(Color.Black.copy(alpha = .6f)).padding(6.dp))
                if (playbackFailed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { retryNonce++; playbackFailed = false; buffering = true; status = tr("Loading…") }) { Text(tr("Retry"), color = Color.White) }
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))) } }) { Text(tr("Open in YouTube"), color = Color.White) }
                }
            }
            if (controls) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = Color.White, fontSize = 36.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 12.dp)); Spacer(Modifier.weight(1f))
                    Icon(SettingsIcon, "Quality", tint = Color.White, modifier = Modifier.size(26.dp).clickable {
                        menu = !menu
                        if (menu && formats == null && req.localPath == null) scope.launch { formats = runCatching { repo.formats(video.id).value }.getOrNull() }
                    }); Spacer(Modifier.width(16.dp))
                    Text("⛶", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { fullscreen = !fullscreen; PlayerScreenFlags.apply(activity, fullscreen) }.padding(end = 8.dp))
                }
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Text("↶", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }); Spacer(Modifier.width(36.dp))
                    if (!buffering) Icon(if (playing) PauseIcon else PlayIcon, null, tint = Color.White, modifier = Modifier.size(56.dp).clickable { if (playing) player.pause() else player.play() })
                    else Spacer(Modifier.size(56.dp))
                    Spacer(Modifier.width(36.dp)); Text("↷", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable { player.seekTo(player.currentPosition + 10_000) })
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("${timeLabel(posMs)} / ${if (durMs > 0) timeLabel(durMs) else video.duration}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Box {
                        LinearProgressIndicator(progress = { if (durMs > 0) (bufMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().align(Alignment.Center).height(2.dp), color = Color.White.copy(alpha = .6f), trackColor = Color.White.copy(alpha = .25f))
                        Slider(if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f, { dragging = true; posMs = (it * durMs).toLong() },
                            Modifier.fillMaxWidth(), enabled = durMs > 0, onValueChangeFinished = { player.seekTo(posMs); dragging = false },
                            colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Color.Transparent))
                    }
                }
            }
            if (menu) Surface(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp), color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("Quality"), color = Color.White, fontWeight = FontWeight.Bold)
                    (listOf(0) + (formats?.video?.map { it.quality } ?: listOf(360, 720, 1080))).forEach { q ->
                        Text(if (q == 0) tr("Auto (fast start)") else "${q}p", color = if (q == quality) Red else Color.White, modifier = Modifier.clickable { quality = q; Settings.playQuality = q; menu = false }.padding(vertical = 8.dp))
                    }
                }
            }
        }
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 3)
                    Text(video.channel, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (req.localPath == null) Icon(DownloadIcon, "Download", tint = Fg, modifier = Modifier.size(28.dp).clickable { showDownload = true })
            }
            HorizontalDivider(color = Muted.copy(alpha = .3f))
            if (req.localPath == null) {
                Text(tr("Up next"), color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize(), state = relList) {
                    items(related, key = { it.id }) { VideoRow(it) { v -> onVideo(PlayRequest(v)) } }
                    if (relBusy) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Red, modifier = Modifier.size(24.dp)) } }
                }
            }
        }
    }
    adBreak?.let { ad -> AdOverlay(ad, "watch") { adBreak = null; player.play() } }
    if (showDownload) DownloadDialog(video, onDismiss = { showDownload = false })
}

@Composable private fun DownloadDialog(video: Video, onDismiss: () -> Unit) {
    val ctx = LocalContext.current; val repo = VideoRepository.instance
    var formats by remember { mutableStateOf<Formats?>(null) }; var error by remember { mutableStateOf(false) }; var tries by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var gateAd by remember { mutableStateOf<AdPublic?>(null) }; var pending by remember { mutableStateOf<FormatOption?>(null) }
    fun start(o: FormatOption, pass: String?) {
        Downloader.enqueue(ctx, video.id, video.title, o.kind, o.quality, pass = pass)
        android.widget.Toast.makeText(ctx, tr("Download started — see Downloads tab"), android.widget.Toast.LENGTH_SHORT).show(); onDismiss()
    }
    LaunchedEffect(tries) { error = false; runCatching { repo.formats(video.id).value }.onSuccess { formats = it }.onFailure { error = true } }
    gateAd?.let { ad -> AdOverlay(ad, "download") { pass -> gateAd = null; pending?.let { start(it, pass) } } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, confirmButton = { TextButton(onDismiss) { Text(tr("Close"), color = Red) } },
        title = { Text(tr("Download"), color = Fg) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!audio, { audio = false }, { Text(tr("Video")) }); FilterChip(audio, { audio = true }, { Text(tr("Audio (MP3)")) })
                }
                Spacer(Modifier.height(8.dp))
                val f = formats
                when {
                    error -> { Text(tr("Connect to the internet to see qualities."), color = Muted); TextButton({ tries++ }) { Text(tr("Retry"), color = Red) } }
                    f == null -> CircularProgressIndicator(color = Red, modifier = Modifier.size(24.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(if (audio) f.audio else f.video) { o ->
                            Row(Modifier.fillMaxWidth().clickable {
                                // Ad gate: if paid ads exist, watch one first; the server issues a single-use pass and refuses ungated downloads.
                                scope.launch { val ad = repo.serveAds("download", 1).firstOrNull(); if (ad == null) start(o, null) else { pending = o; gateAd = ad } }
                            }.padding(vertical = 12.dp)) {
                                Text(o.label, color = Fg, modifier = Modifier.weight(1f)); Text(formatSize(o.sizeBytes).let { if (it.isNotEmpty()) "~$it" else "" }, color = Muted)
                            }
                        }
                    }
                }
            }
        })
}

private fun timeLabel(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }

object PlayerScreenFlags {
    fun apply(a: Activity?, full: Boolean) {
        a ?: return
        a.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (full) { c.hide(WindowInsetsCompat.Type.systemBars()); c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } else c.show(WindowInsetsCompat.Type.systemBars())
    }
    fun restore(a: Activity?) = apply(a, false)
}

private fun parseDuration(d: String): Long = d.split(":").mapNotNull { it.toLongOrNull() }.fold(0L) { acc, n -> acc * 60 + n } * 1000
