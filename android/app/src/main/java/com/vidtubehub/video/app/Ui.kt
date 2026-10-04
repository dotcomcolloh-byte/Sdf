package com.vidtubehub.video.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidtubehub.video.app.data.Video
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter

val Red = Color(0xFFFF1744)
val LocalDark = compositionLocalOf { true }
val Bg: Color @Composable get() = if (LocalDark.current) Color(0xFF070C11) else Color(0xFFF6F7F9)
val Panel: Color @Composable get() = if (LocalDark.current) Color(0xFF121A24) else Color(0xFFFFFFFF)
val Muted: Color @Composable get() = if (LocalDark.current) Color(0xFF9FAABC) else Color(0xFF5B6675)
val Fg: Color @Composable get() = if (LocalDark.current) Color.White else Color(0xFF10151C)

/** [localPath] != null => play a downloaded file offline. */
data class PlayRequest(val video: Video, val localPath: String? = null, val audioOnly: Boolean = false, val startMs: Long = 0)

/** Infinite scrolling: calls [onLoadMore] whenever the last visible item is within 4 items of the end. */
@Composable fun InfiniteEffect(state: LazyListState, key: Any?, onLoadMore: () -> Unit) {
    val cb by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, key) {
        snapshotFlow { val li = state.layoutInfo; (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount }
            .filter { (last, total) -> total > 0 && last >= total - 4 }
            .collect { cb() }
    }
}

@Composable fun VideoRow(v: Video, onVideo: (Video) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onVideo(v) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Panel)) {
            AsyncImage(v.thumbnailUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (v.duration.isNotBlank()) Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color.Black.copy(alpha = .8f), shape = RoundedCornerShape(4.dp)) {
                Text(v.duration, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(v.channel, v.views, v.age).filter { it.isNotBlank() }.joinToString("  •  "), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
        }
    }
}

@Composable fun VideoSkeleton() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Panel)); Spacer(Modifier.width(12.dp))
        Column { Box(Modifier.width(150.dp).height(14.dp).background(Panel)); Spacer(Modifier.height(8.dp)); Box(Modifier.width(90.dp).height(12.dp).background(Panel)) }
    }
}
