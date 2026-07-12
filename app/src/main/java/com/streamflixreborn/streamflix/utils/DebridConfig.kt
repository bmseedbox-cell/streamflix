package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DebridConfig
 * ------------
 * Pulls the operator-provided debrid key from your panel and applies it, so end
 * users don't have to enter anything. Call this once after a successful login
 * (you know the panel base URL + the user's credentials at that point):
 *
 *     DebridConfig.fetchFromPanel(
 *         baseUrl = "https://gmapps.org/panels/streamflix/gmiptv/",
 *         username = user,
 *         password = pass,
 *     )
 *
 * It calls  <baseUrl>debrid.php?username=U&password=P  which returns:
 *     { "service": "premiumize", "key": "xxxxx", "torrentio": "https://torrentio.strem.fun" }
 *
 * Safe to call anytime; failures are swallowed and just leave debrid off.
 */
object DebridConfig {

    private const val TAG = "DebridConfig"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun fetchFromPanel(
        baseUrl: String,
        username: String,
        password: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl.trimEnd('/')
            val url = "$base/debrid.php" +
                "?username=${enc(username)}&password=${enc(password)}"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "debrid.php returned HTTP ${response.code}")
                    return@withContext false
                }
                val body = response.body?.string() ?: return@withContext false
                val cfg = gson.fromJson(body, PanelDebridConfig::class.java)
                    ?: return@withContext false

                if (cfg.service.isNullOrBlank() || cfg.key.isNullOrBlank()) {
                    // Operator hasn't set a key; make sure debrid stays off.
                    UserPreferences.debridService = "off"
                    return@withContext false
                }

                DebridResolver.applyRemoteConfig(
                    service = cfg.service,
                    apiKey = cfg.key,
                    torrentioBaseUrl = cfg.torrentio,
                )
                Log.i(TAG, "Applied debrid config from panel (${cfg.service}).")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch debrid config: ${e.message}", e)
            false
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private data class PanelDebridConfig(
        @SerializedName("service") val service: String?,
        @SerializedName("key") val key: String?,
        @SerializedName("torrentio") val torrentio: String?,
    )
}
