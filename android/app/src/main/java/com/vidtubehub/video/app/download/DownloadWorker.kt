package com.vidtubehub.video.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.vidtubehub.video.app.data.VideoRepository
import com.vidtubehub.video.app.util.isOnline
import com.vidtubehub.video.app.util.onlineFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 1) asks the server to prepare (download+merge) the chosen quality, polling real progress
 * 2) pulls the finished file with HTTP Range, appending to a .part file
 * On any network error it waits for connectivity and continues from the last byte, no restart.
 */
class DownloadWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val key = inputData.getString("key").orEmpty()
    private val repo get() = VideoRepository.instance
    private var lastUi = 0L

    override suspend fun doWork(): Result {
        var d = DownloadStore.get(key) ?: return Result.failure()
        if (d.status == "done") return Result.success()
        runCatching { setForeground(foregroundInfo(d)) }
        var serverErrors = 0
        try {
            while (true) {
                try {
                    val file = prepareOnServer(d) ?: return Result.failure()
                    d = DownloadStore.get(key) ?: return Result.failure()
                    fetch(d, file)
                    return Result.success()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    d = DownloadStore.get(key) ?: return Result.failure()
                    if (e is com.vidtubehub.video.app.data.ApiException && e.code == 403) return fail(d, "Watch a short ad to download — open the video and try again")
                    if (ctx.isOnline() && ++serverErrors > 6) return fail(d, e.message ?: "download error")
                    DownloadStore.upsert(d.copy(status = "waiting", error = null))
                    if (!ctx.isOnline()) ctx.onlineFlow().first { it } else delay(3000L * serverErrors)
                    delay(800)
                }
            }
        } catch (e: CancellationException) { // WorkManager stopped us (constraint lost / user cancel): keep .part, mark waiting
            withContext(NonCancellable) { DownloadStore.get(key)?.let { if (it.status != "done") DownloadStore.upsert(it.copy(status = "waiting")) } }
            throw e
        }
    }

    private fun fail(d: LocalDownload, msg: String): Result { DownloadStore.upsert(d.copy(status = "failed", error = msg)); return Result.failure() }

    /** Returns the server file name once ready, or null after marking failure. */
    private suspend fun prepareOnServer(d: LocalDownload): String? {
        var job = repo.serverJob(d.key) ?: repo.requestDownload(d.id, d.title, d.kind, d.quality, d.pass) // ad pass is single-use: only sent when creating the job
        while (job.status != "ready") {
            if (job.status == "failed") { fail(d, job.error ?: "server failed"); return null }
            ui(DownloadStore.get(key)!!.copy(status = "preparing", progress = job.progress / 2), force = true)
            delay(1500)
            job = repo.serverJob(key) ?: repo.requestDownload(d.id, d.title, d.kind, d.quality, null)
        }
        return job.fileName
    }

    private suspend fun fetch(d: LocalDownload, serverFile: String) = withContext(Dispatchers.IO) {
        val dir = ctx.getExternalFilesDir("downloads") ?: ctx.filesDir
        val part = File(dir, "$key.part")
        val dest = File(dir, "$key.${serverFile.substringAfterLast('.')}")
        val have = if (part.exists()) part.length() else 0L
        val conn = URL(repo.absolute("/api/file/$serverFile")).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        // Abort the blocked read immediately when the network drops instead of waiting for the socket timeout
        val watcher = launch { ctx.onlineFlow().first { !it }; conn.disconnect() }
        try {
            val code = conn.responseCode
            var total = 0L; var append = false
            when (code) {
                200 -> total = conn.contentLengthLong
                206 -> { append = true; total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (have + conn.contentLengthLong) }
                416 -> { if (have > 0) { part.renameTo(dest); finish(d, dest); return@withContext } else throw IOException("HTTP 416") }
                else -> throw IOException("HTTP $code")
            }
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        ui(d.copy(status = "downloading", bytes = done, total = total, progress = 50 + if (total > 0) (done * 50 / total).toInt() else 0))
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("connection closed early") // resume on next loop
            dest.delete(); part.renameTo(dest)
            finish(d, dest)
        } finally { watcher.cancel(); conn.disconnect() }
    }

    private fun finish(d: LocalDownload, f: File) =
        DownloadStore.upsert(d.copy(pass = null, status = "done", progress = 100, bytes = f.length(), total = f.length(), path = f.path, error = null))

    private fun ui(d: LocalDownload, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUi < 500) return
        lastUi = now; DownloadStore.upsert(d)
    }

    private fun foregroundInfo(d: LocalDownload): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(ctx, "dl").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(d.title)
            .setContentText("Downloading ${if (d.kind == "audio") "audio" else "${d.quality}p"}").setOngoing(true).setProgress(0, 0, true).build()
        return ForegroundInfo(key.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
