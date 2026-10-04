package com.vidtubehub.video.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import java.io.File

@OptIn(UnstableApi::class)
object PlayerCache {
    private var cache: SimpleCache? = null

    /** 1 GB on-device LRU video cache: replays/seeks/next-video start instantly and work offline for cached ranges. */
    @Synchronized fun get(ctx: Context): SimpleCache = cache ?: SimpleCache(
        File(ctx.cacheDir, "exo"), LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024), StandaloneDatabaseProvider(ctx)
    ).also { cache = it }

    private fun cachedFactory(ctx: Context) = CacheDataSource.Factory().setCache(get(ctx))
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(true)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Start after 1.5s buffered (fast start), then keep buffering up to 2 minutes ahead (pre-play ahead). */
    fun newPlayer(ctx: Context): ExoPlayer {
        val lc = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(30_000, 120_000, 1_500, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true).setBackBuffer(30_000, true).build()
        return ExoPlayer.Builder(ctx).setLoadControl(lc).setMediaSourceFactory(DefaultMediaSourceFactory(cachedFactory(ctx))).build()
    }

    /** HLS: playlists (live-style, keep growing) must bypass the cache; init + segments are cached. */
    fun hlsSource(ctx: Context, url: String): HlsMediaSource {
        val cached = cachedFactory(ctx); val plain = DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000))
        val f = HlsDataSourceFactory { type -> if (type == C.DATA_TYPE_MANIFEST) plain.createDataSource() else cached.createDataSource() }
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).setTargetOffsetMs(20_000).build()).build()
        return HlsMediaSource.Factory(f).setAllowChunklessPreparation(true).createMediaSource(item)
    }

    /** Warms the first [bytes] of a URL into the cache so the next video starts with zero wait. */
    suspend fun prefetch(ctx: Context, url: String, bytes: Long = C.LENGTH_UNSET.toLong()) {
        runCatching {
            runInterruptible(Dispatchers.IO) {
                CacheWriter(cachedFactory(ctx).createDataSource(), DataSpec.Builder().setUri(url).setLength(bytes).build(), null, null).cache()
            }
        }
    }

    fun clear(ctx: Context) { val c = get(ctx); c.keys.toList().forEach { runCatching { c.removeResource(it) } } }
}
