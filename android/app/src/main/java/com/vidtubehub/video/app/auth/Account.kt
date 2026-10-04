package com.vidtubehub.video.app.auth

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.vidtubehub.video.app.BuildConfig
import com.vidtubehub.video.app.data.UserInfo
import com.vidtubehub.video.app.data.VideoRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Google sign-in (Credential Manager) -> ID token -> backend verifies it -> backend session token. */
object Account {
    private lateinit var sp: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val _user = mutableStateOf<UserInfo?>(null)
    val user: UserInfo? get() = _user.value
    var token: String? = null; private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("account", Context.MODE_PRIVATE)
        token = sp.getString("token", null)
        _user.value = sp.getString("user", null)?.let { runCatching { json.decodeFromString<UserInfo>(it) }.getOrNull() }
        if (token == null) _user.value = null
    }

    suspend fun signIn(activity: Activity): UserInfo {
        require(BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) { "GOOGLE_WEB_CLIENT_ID missing: build with -PgoogleWebClientId=..." }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val res = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val cred = res.credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential" }
        val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
        val r = VideoRepository.instance.login(idToken) // server verifies signature, audience, expiry, email_verified
        token = r.token; _user.value = r.user
        sp.edit().putString("token", r.token).putString("user", json.encodeToString(r.user)).apply()
        return r.user
    }

    suspend fun signOut(ctx: Context) {
        runCatching { CredentialManager.create(ctx).clearCredentialState(ClearCredentialStateRequest()) }
        clear()
    }
    fun clear() { token = null; _user.value = null; sp.edit().clear().apply() }
}
