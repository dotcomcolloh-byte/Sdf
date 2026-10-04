package com.vidtubehub.video.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.vidtubehub.video.app.data.*
import com.vidtubehub.video.app.ads.*
import com.vidtubehub.video.app.player.PlayerCache
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import com.vidtubehub.video.app.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { VidTubeHubApp() }
    }
}

@Composable fun VidTubeHubApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    var campaigns by remember { mutableStateOf(false) }
    val dark = when (Settings.theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val act = LocalContext.current as? android.app.Activity
    SideEffect { // status/nav bars follow the theme
        act?.window?.let { w ->
            w.statusBarColor = (if (dark) 0xFF070C11 else 0xFFF6F7F9).toInt(); w.navigationBarColor = w.statusBarColor
            WindowCompat.getInsetsController(w, w.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Red, background = Bg, surface = Panel) else lightColorScheme(primary = Red, background = Bg, surface = Panel)) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                val p = playing
                if (campaigns) CampaignsScreen { campaigns = false }
                else if (p != null) WatchScreen(p, onBack = { playing = null }, onVideo = { playing = it })
                else Scaffold(containerColor = Bg, bottomBar = { BottomNav(tab) { tab = it } }) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> HomeScreen { playing = PlayRequest(it) }
                            1 -> HistoryScreen { playing = it }
                            2 -> DownloadsScreen { playing = it }
                            else -> SettingsScreen { campaigns = true }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HistoryScreen(onPlay: (PlayRequest) -> Unit) {
    val items by HistoryStore.items.collectAsState()
    SimpleScreen(tr("History")) {
        if (items.isEmpty()) Text(tr("Your watch history will appear here."), color = Muted)
        else {
            TextButton({ HistoryStore.clear() }) { Text(tr("Clear all"), color = Red) }
            LazyColumn {
                items(items, key = { it.video.id }) { e ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { VideoRow(e.video) { onPlay(PlayRequest(it, startMs = e.positionMs)) } }
                            Text("✕", color = Muted, fontSize = 18.sp, modifier = Modifier.clickable { HistoryStore.remove(e.video.id) }.padding(12.dp))
                        }
                        if (e.durationMs > 0 && e.positionMs > 0)
                            LinearProgressIndicator(progress = { (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp), color = Red)
                    }
                }
            }
        }
    }
}

@Composable private fun HomeScreen(onVideo: (Video) -> Unit) {
    val ctx = LocalContext.current; val repo = VideoRepository.instance; val scope = rememberCoroutineScope()
    val online by remember { ctx.onlineFlow() }.collectAsState(initial = ctx.isOnline())
    val list = rememberLazyListState()
    var feed by remember { mutableStateOf<Feed?>(null) }
    var items by remember { mutableStateOf<List<Video>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }; var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }; var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }; var stale by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("All") }; var query by remember { mutableStateOf("") }; var showSearch by remember { mutableStateOf(false) }
    var homeAds by remember { mutableStateOf<List<AdPublic>>(emptyList()) } // append-only so slots stay stable while scrolling
    var openAdItem by remember { mutableStateOf<AdPublic?>(null) }
    LaunchedEffect(page, online) { if (online && page >= 0 && homeAds.size < (page + 1) * 4) homeAds = homeAds + repo.serveAds("home", 4) }
    var gen by remember { mutableIntStateOf(0) } // invalidates in-flight loads when the query changes
    val defaultQ = feed?.defaultQuery?.ifBlank { null } ?: "travel music gaming news"
    fun currentQ() = if (category == "All") defaultQ else category

    fun loadPage(reset: Boolean) {
        if (!reset && (loading || loadingMore || !hasMore)) return
        val my = if (reset) ++gen else gen
        scope.launch {
            if (reset) { loading = true; items = emptyList(); page = -1; hasMore = true } else loadingMore = true
            failed = false
            runCatching { repo.videos(currentQ(), page + 1) }.onSuccess { r ->
                if (my != gen) return@onSuccess
                val fresh = r.value.items.filterNot { n -> items.any { it.id == n.id } }
                items = items + fresh; page = r.value.page; stale = r.stale
                hasMore = r.value.hasMore && fresh.isNotEmpty() && !r.stale // offline cache has no "next page"
            }.onFailure { if (my == gen) { failed = true; hasMore = false } }
            if (my == gen) { loading = false; loadingMore = false }
        }
    }
    LaunchedEffect(Unit) { // first paint: feed (hero + first page), cached for offline
        runCatching { repo.feed() }.onSuccess { r -> feed = r.value; items = r.value.trending; page = 0; hasMore = r.value.trending.size >= PAGE_SIZE && !r.stale; stale = r.stale }
            .onFailure { failed = true }
        loading = false
    }
    LaunchedEffect(online) { if (online && (stale || failed) && !loading) { if (feed == null) { loading = true; runCatching { repo.feed() }.onSuccess { r -> feed = r.value; items = r.value.trending; page = 0; hasMore = true; failed = false; stale = r.stale }; loading = false } else loadPage(true) } }
    InfiniteEffect(list, items.size) { loadPage(false) }

    HomeAdOverlay(openAdItem) { openAdItem = null }
    fun select(c: String) { category = c; loadPage(true) }
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item { Header { showSearch = !showSearch } }
        if (!online) item { Text(tr("You're offline — showing saved videos"), color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFF7A1F2B)).padding(8.dp)) }
        if (showSearch) item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Search videos")) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Red, focusedLabelColor = Red))
                Spacer(Modifier.width(8.dp))
                Button({ if (query.isNotBlank()) { category = query.trim(); loadPage(true) } }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text(tr("Go")) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (feed?.categories ?: listOf("All", "Music", "Gaming", "Travel", "Food", "Tech", "News")).forEach { c ->
                    Surface(color = if (c == category) Red else Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable { select(c) }) {
                        Text(tr(c), color = if (c == category) Color.White else Fg, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (category == "All") feed?.hero?.let { h -> item { Hero(h, onVideo) } }
        item {
            Text(if (category == "All") tr("Trending") else tr(category), color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        }
        if (loading) items(5) { VideoSkeleton() }
        else if (items.isEmpty()) item {
            Column(Modifier.padding(24.dp)) {
                Text(if (failed) tr("Couldn't load videos. Check your connection.") else tr("No public videos found."), color = Muted)
                if (failed) Button({ loadPage(true) }, colors = ButtonDefaults.buttonColors(containerColor = Red), modifier = Modifier.padding(top = 12.dp)) { Text(tr("Retry")) }
            }
        } else {
            // a small "Sponsored" card after every 5 videos (only if paid ads exist)
            val slots = items.chunked(5)
            slots.forEachIndexed { k, chunk ->
                items(chunk, key = { it.id }) { VideoRow(it, onVideo) }
                if (chunk.size == 5) homeAds.getOrNull(k)?.let { ad -> item(key = "ad_${k}_${ad.id}") { SponsoredCard(ad) { openAdItem = ad } } }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    loadingMore -> CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
                    failed && items.isNotEmpty() -> TextButton({ hasMore = true; loadPage(false) }) { Text(tr("Couldn't load more — tap to retry"), color = Red) }
                    stale && items.isNotEmpty() -> Text(tr("Offline — that's all that's saved"), color = Muted, fontSize = 13.sp)
                    !hasMore && items.isNotEmpty() -> Text(tr("You've reached the end"), color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable private fun HomeAdOverlay(ad: AdPublic?, onClose: () -> Unit) { if (ad != null) AdOverlay(ad, "home") { onClose() } }

@Composable private fun Header(onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Red), contentAlignment = Alignment.Center) { Icon(com.vidtubehub.video.app.util.PlayIcon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp)); Text("VidTube Hub", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
        Icon(com.vidtubehub.video.app.util.SearchIcon, "Search", tint = Fg, modifier = Modifier.size(26.dp).clickable { onSearch() })
    }
}

@Composable private fun Hero(v: Video, onVideo: (Video) -> Unit) {
    Box(Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).clickable { onVideo(v) }) {
        AsyncImage(v.thumbnailUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .3f)))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Surface(color = Red, shape = RoundedCornerShape(12.dp)) { Text(v.channel.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
            Text(v.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable fun SimpleScreen(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) { Text(title, color = Fg, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp)); content() }
}

@Composable private fun SettingsScreen(onCampaigns: () -> Unit) {
    val ctx = LocalContext.current
    var wifi by remember { mutableStateOf(Settings.wifiOnly) }; var q by remember { mutableIntStateOf(Settings.playQuality) }
    var auto by remember { mutableStateOf(Settings.autoplay) }; var msg by remember { mutableStateOf("") }; var langDialog by remember { mutableStateOf(false) }
    SimpleScreen(tr("Settings")) {
        Button(onCampaigns, Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
            Text(tr("Create campaigns — reach more users instantly"), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Text(tr("Theme"), color = Fg, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Dark", "Light").forEach { t -> FilterChip(Settings.theme == t, { Settings.theme = t }, { Text(tr(t)) }) }
        }
        SettingRow(tr("Language"), if (Settings.language == "System") tr("System") else Settings.language) { langDialog = true }
        SettingSwitch(tr("Download over Wi-Fi only"), wifi) { wifi = it; Settings.wifiOnly = it }
        SettingSwitch(tr("Autoplay next video"), auto) { auto = it; Settings.autoplay = it }
        val qs = listOf(0, 360, 480, 720, 1080)
        SettingRow(tr("Playback quality"), if (q == 0) tr("Auto (fast start)") else "${q}p") { q = qs[(qs.indexOf(q) + 1) % qs.size]; Settings.playQuality = q }
        SettingRow(tr("Cache"), "${tr("Clear video + list cache")} $msg") { PlayerCache.clear(ctx); VideoRepository.instance.clearApiCache(); msg = "· ${tr("cleared")}" }
    }
    if (langDialog) AlertDialog(onDismissRequest = { langDialog = false }, containerColor = Panel, confirmButton = { TextButton({ langDialog = false }) { Text(tr("Close"), color = Red) } },
        title = { Text(tr("Language"), color = Fg) },
        text = { Column { LANGUAGES.forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { Settings.language = l; langDialog = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(Settings.language == l, { Settings.language = l; langDialog = false }); Text(if (l == "System") tr("System") else l, color = Fg)
            } } } })
}
@Composable private fun SettingSwitch(label: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, color = Fg, fontSize = 16.sp, modifier = Modifier.weight(1f)); Switch(v, on, colors = SwitchDefaults.colors(checkedTrackColor = Red)) }
}
@Composable private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp)) { Text(label, color = Fg, fontSize = 16.sp); Text(value, color = Muted, fontSize = 13.sp) }
}

@Composable private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Bg) {
        listOf("Home" to com.vidtubehub.video.app.util.HomeIcon, "History" to com.vidtubehub.video.app.util.HistoryIcon, "Downloads" to com.vidtubehub.video.app.util.DownloadIcon, "Settings" to com.vidtubehub.video.app.util.SettingsIcon).forEachIndexed { i, (label, icon) ->
            NavigationBarItem(selected == i, { onSelect(i) }, icon = { Icon(icon, label, Modifier.size(24.dp)) }, label = { Text(tr(label), fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Red, selectedTextColor = Red, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Color.Transparent))
        }
    }
}
