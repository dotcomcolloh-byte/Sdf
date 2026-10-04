package com.vidtubehub.video.app.data

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

const val DEFAULT_URL = "http://10.0.2.2:8080" // emulator → host machine. On a real phone set your PC/server LAN address in Settings.

object AppGraph {
    lateinit var app: Application
    val repo by lazy { VideoRepository(app) }
    fun init(a: Application) { app = a; Settings.init(a) }
}

/** Live connectivity. `online` flips back to true the moment a network returns → players/downloads resume. */
object NetworkMonitor {
    val online = MutableStateFlow(true)
    val unmetered = MutableStateFlow(true)

    fun start(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.let { online.value = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET); unmetered.value = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            ?: run { online.value = false }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = true }
            override fun onLost(network: Network) { online.value = false }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
                online.value = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                unmetered.value = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        })
    }
    suspend fun awaitOnline() { online.first { it } }
}

/** Local watch history (also usable offline). */
object History {
    val items = mutableStateListOf<Video>()
    private lateinit var file: File
    private val json = Json { ignoreUnknownKeys = true }
    fun load(ctx: Context) { file = File(ctx.filesDir, "history.json"); runCatching { items.addAll(json.decodeFromString<List<Video>>(file.readText())) } }
    fun add(v: Video) {
        items.removeAll { it.id == v.id }; items.add(0, v)
        while (items.size > 100) items.removeAt(items.lastIndex)
        runCatching { file.writeText(json.encodeToString(items.toList())) }
    }
    fun clear() { items.clear(); runCatching { file.delete() } }
}
