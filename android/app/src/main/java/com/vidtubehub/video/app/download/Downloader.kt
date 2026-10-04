package com.vidtubehub.video.app.download

import android.content.Context
import androidx.work.*
import com.vidtubehub.video.app.data.Settings
import java.io.File
import java.util.concurrent.TimeUnit

object Downloader {
    fun enqueue(ctx: Context, id: String, title: String, kind: String, quality: Int, replace: Boolean = false, pass: String? = null) {
        val key = "${id}_${kind.first()}$quality"
        val old = DownloadStore.get(key)
        if (old != null && old.status == "done" && !replace) return
        DownloadStore.upsert(LocalDownload(key, id, title, "https://i.ytimg.com/vi/$id/hqdefault.jpg", kind, quality, status = "queued", pass = pass))
        schedule(ctx, key, replace)
    }

    private fun schedule(ctx: Context, key: String, replace: Boolean) {
        val net = if (Settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("key" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()) // waits for network by itself
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(key, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    fun retry(ctx: Context, d: LocalDownload) = enqueue(ctx, d.id, d.title, d.kind, d.quality, replace = true)

    fun cancel(ctx: Context, key: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(key)
        DownloadStore.get(key)?.path?.let { File(it).delete() }
        File(ctx.getExternalFilesDir("downloads") ?: ctx.filesDir, "$key.part").delete()
        DownloadStore.remove(key)
    }

    fun resumeAll(ctx: Context) {
        DownloadStore.items.value.filter { it.status != "done" && it.status != "failed" }.forEach { schedule(ctx, it.key, false) }
    }
}
