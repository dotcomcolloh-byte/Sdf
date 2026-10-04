package com.vidtubehub.video.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

/**
 * Everything that makes playback fast:
 *  • SimpleCache (1 GB LRU) – every byte played is kept on disk, so replays / seeks back are instant and work offline.
 *  • Large read-ahead buffer (30–60 s) with a 0.8 s start threshold, so playback begins almost immediately but keeps buffering far ahead.
 *  • prefetch() – warms the first seconds of the *next* video into the same cache before the user taps it.
 *  • Video + audio are separate streams merged by the player (MergingMediaSource) → any quality, no server-side transcode wait.
 */
object PlayerEngine {
    private const val MAX_CACHE = 1024L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    fun cache(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(ctx.applicationContext.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(MAX_CACHE), StandaloneDatabaseProvider(ctx.applicationContext)).also { cache = it }
    }

    private fun httpFactory() = DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(true)

    private fun cachedFactory(ctx: Context) = CacheDataSource.Factory()
        .setCache(cache(ctx))
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(ctx.applicationContext, httpFactory()))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun newPlayer(ctx: Context): ExoPlayer {
        val load = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(30_000, 60_000, 800, 2_500) // min, max, start-playback, resume-after-stall
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, false)
            .build()
        return ExoPlayer.Builder(ctx.applicationContext)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    fun networkSource(ctx: Context, videoUrl: String, audioUrl: String?): MediaSource {
        val f = ProgressiveMediaSource.Factory(cachedFactory(ctx))
        val v = f.createMediaSource(MediaItem.fromUri(videoUrl))
        return if (audioUrl == null) v else MergingMediaSource(v, f.createMediaSource(MediaItem.fromUri(audioUrl)))
    }

    /** Downloaded files play straight from disk (not through the cache, to avoid duplicating bytes). */
    fun localSource(ctx: Context, path: String): MediaSource =
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx.applicationContext)).createMediaSource(MediaItem.fromUri(Uri.fromFile(File(path))))

    /** Pre-play-ahead: download the first [bytes] of each URL into the cache. Cancellable (interrupts the blocking read). */
    suspend fun prefetch(ctx: Context, urls: List<String>, bytes: Long = 1_800_000) {
        for (u in urls) {
            try {
                runInterruptible(Dispatchers.IO) {
                    val ds = cachedFactory(ctx).createDataSource()
                    CacheWriter(ds, DataSpec.Builder().setUri(u).setLength(bytes).build(), null, null).cache()
                }
            } catch (e: IOException) { /* best-effort */ }
        }
    }

    fun sizeBytes(ctx: Context) = cache(ctx).cacheSpace
    fun clear(ctx: Context) { val c = cache(ctx); c.keys.toList().forEach { runCatching { c.removeResource(it) } } }
}
