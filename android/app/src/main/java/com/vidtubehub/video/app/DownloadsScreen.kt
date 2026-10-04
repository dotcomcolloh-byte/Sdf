package com.vidtubehub.video.app

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidtubehub.video.app.data.Video
import com.vidtubehub.video.app.download.*
import com.vidtubehub.video.app.util.*
import java.io.File

@Composable fun DownloadsScreen(onPlay: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current
    val items by DownloadStore.items.collectAsState()
    val online by remember { ctx.onlineFlow() }.collectAsState(initial = ctx.isOnline())
    SimpleScreen(tr("Downloads")) {
        if (items.isEmpty()) Text(tr("Tap the download icon on a video to save video or audio."), color = Muted)
        LazyColumn {
            items(items, key = { it.key }) { d ->
                val file = d.path?.let { File(it) }
                Row(Modifier.fillMaxWidth().clickable(enabled = d.status == "done" && file?.exists() == true) {
                    onPlay(PlayRequest(Video(d.id, d.title, "Downloaded", d.thumbnailUrl, "", "", ""), d.path, d.kind == "audio"))
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(d.thumbnailUrl, null, Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        val label = if (d.kind == "audio") "${d.quality} kbps MP3" else "${d.quality}p video"
                        val state = when (d.status) {
                            "done" -> "${tr("Saved")} · ${formatSize(d.bytes)}"
                            "preparing" -> "${tr("Preparing on server…")} ${d.progress}%"
                            "downloading" -> "${formatSize(d.bytes)} / ${formatSize(d.total)}"
                            "waiting" -> if (online) tr("Reconnecting…") else tr("Paused — will resume when online")
                            "failed" -> "${tr("Failed")}: ${d.error ?: "error"}"
                            else -> if (online) tr("Queued") else tr("Waiting for network")
                        }
                        Text("$label · $state", color = if (d.status == "failed") Red else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        if (d.status != "done" && d.status != "failed") LinearProgressIndicator(progress = { d.progress / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp), color = Red)
                    }
                    if (d.status == "failed") Text("↻", color = Fg, fontSize = 24.sp, modifier = Modifier.clickable { Downloader.retry(ctx, d) }.padding(8.dp))
                    Text("✕", color = Muted, fontSize = 20.sp, modifier = Modifier.clickable { Downloader.cancel(ctx, d.key) }.padding(8.dp))
                }
            }
        }
    }
}
