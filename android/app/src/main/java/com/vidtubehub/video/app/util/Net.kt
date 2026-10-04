package com.vidtubehub.video.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.callbackFlow

fun Context.isOnline(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java)
    val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
fun Context.isMetered(): Boolean = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

fun Context.onlineFlow(): Flow<Boolean> = callbackFlow {
    val cm = getSystemService(ConnectivityManager::class.java)
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(isOnline()) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(isOnline()) }
        override fun onLost(network: Network) { trySend(false) }
    }
    cm.registerDefaultNetworkCallback(cb)
    trySend(isOnline())
    awaitClose { cm.unregisterNetworkCallback(cb) }
}.distinctUntilChanged()

/** Retries [block] forever while offline (resuming the instant the network returns); gives up after [maxOnlineAttempts] real server errors. */
suspend fun <T> retryWhenOnline(ctx: Context, maxOnlineAttempts: Int = 4, onWait: () -> Unit = {}, block: suspend () -> T): T {
    var attempts = 0
    while (true) {
        try { return block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            onWait()
            if (!ctx.isOnline()) ctx.onlineFlow().first { it } else if (++attempts >= maxOnlineAttempts) throw e else delay(2500)
        }
    }
}

fun formatSize(b: Long?): String = when {
    b == null || b <= 0 -> ""
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%d KB".format(b / 1024)
}
