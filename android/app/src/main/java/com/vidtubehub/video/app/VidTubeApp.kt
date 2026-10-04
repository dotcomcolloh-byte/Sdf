package com.vidtubehub.video.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.vidtubehub.video.app.data.*
import com.vidtubehub.video.app.download.Downloader
import com.vidtubehub.video.app.download.DownloadStore

class VidTubeApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this); DownloadStore.init(this); HistoryStore.init(this); com.vidtubehub.video.app.auth.Account.init(this)
        VideoRepository.instance = VideoRepository(this)
        Downloader.resumeAll(this) // re-arm anything interrupted by app kill / reboot
    }
    // Thumbnails are cached on disk regardless of server cache headers => visible offline.
    override fun newImageLoader() = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false).crossfade(true).build()
}
