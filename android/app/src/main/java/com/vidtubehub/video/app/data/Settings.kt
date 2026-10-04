package com.vidtubehub.video.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.vidtubehub.video.app.BuildConfig

object Settings {
    private lateinit var sp: SharedPreferences
    private val _theme = mutableStateOf("System"); private val _lang = mutableStateOf("System")
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = sp.getString("theme", "System")!!; _lang.value = sp.getString("lang", "System")!!
    }
    /** Fixed at build time (-PapiBaseUrl=...). */
    val baseUrl: String get() = BuildConfig.API_BASE_URL.trimEnd('/')
    /** System | Dark | Light — Compose state, so changing it recomposes the whole app instantly. */
    var theme: String get() = _theme.value; set(v) { _theme.value = v; sp.edit().putString("theme", v).apply() }
    /** System | English | Español | Français | Deutsch | Português */
    var language: String get() = _lang.value; set(v) { _lang.value = v; sp.edit().putString("lang", v).apply() }
    /** Random per-install id: binds ad view tokens / download passes to this device. */
    val deviceId: String get() = sp.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }
    var wifiOnly: Boolean get() = sp.getBoolean("wifiOnly", false); set(v) = sp.edit().putBoolean("wifiOnly", v).apply()
    var playQuality: Int get() = sp.getInt("playQuality", 0); set(v) = sp.edit().putInt("playQuality", v).apply()
    var autoplay: Boolean get() = sp.getBoolean("autoplay", true); set(v) = sp.edit().putBoolean("autoplay", v).apply()
}
