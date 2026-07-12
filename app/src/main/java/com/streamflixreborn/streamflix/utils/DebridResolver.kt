package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DebridResolver
 * --------------
 * Adds debrid (Premiumize / Real-Debrid / AllDebrid) sources to the per-title
 * server list, for ANY provider.
 *
 * How it works:
 *   - Every title in StreamFlix carries an IMDb id (Video.Type.Movie.imdbId /
 *     Episode.tvShow.imdbId). We use that id to query a Torrentio-compatible
 *     endpoint, which does the torrent indexing AND the debrid resolution and
 *     hands back ready-to-play direct links.
 *   - Each returned stream becomes a Video.Server whose `src` is the direct
 *     link, prepended to the provider's own servers.
 *
 * Configure it in one of two ways:
 *   1. Settings (UserPreferences.debridService + debridApiKey), or
 *   2. From your panel, by calling DebridResolver.applyRemoteConfig(...) after
 *      login (see DebridConfig.fetchFromPanel).
 *
 * The Torrentio base URL is configurable (UserPreferences.torrentioBaseUrl) so
 * you can point it at a self-hosted Torrentio / KnightCrawler instance and be
 * fully independent of the public one.
 */
object DebridResolver {

    private const val TAG = "DebridResolver"
    private const val SERVER_ID_PREFIX = "debrid::"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** Debrid is on only when a service + key are configured. */
    fun isEnabled(): Boolean =
        UserPreferences.debridService != "off" &&
            UserPreferences.debridApiKey.isNotBlank()

    /** True for servers this resolver created. */
    fun isDebridServer(server: Video.Server): Boolean =
        server.id.startsWith(SERVER_ID_PREFIX)

    /**
     * Build the extra debrid servers for a title.
     * Returns an empty list (never throws) when disabled, when there is no IMDb
     * id, or when the lookup fails — so it can never break normal playback.
     */
    suspend fun getServers(videoType: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext emptyList()

        val (imdbId, streamPath) = when (videoType) {
            is Video.Type.Movie ->
                videoType.imdbId to "movie/${videoType.imdbId}"

            is Video.Type.Episode ->
                videoType.tvShow.imdbId to
                    "series/${videoType.tvShow.imdbId}:${videoType.season.number}:${videoType.number}"
        }

        if (imdbId.isNullOrBlank()) {
            Log.i(TAG, "No IMDb id for this title; skipping debrid lookup.")
            return@withContext emptyList()
        }

        val service = UserPreferences.debridService            // premiumize | realdebrid | alldebrid
        val key = UserPreferences.debridApiKey
        val base = UserPreferences.torrentioBaseUrl.trimEnd('/')
        val url = "$base/$service=$key/stream/$streamPath.json"

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "StreamFlix")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Debrid endpoint returned HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val parsed = gson.fromJson(bodyStr, TorrentioResponse::class.java)
                val streams = parsed?.streams ?: return@withContext emptyList()

                val servers = streams.mapIndexedNotNull { index, s ->
                    val link = s.url?.trim() ?: return@mapIndexedNotNull null
                    // Only real, already-resolved direct links (skip magnets and
                    // "download to debrid" placeholders that aren't playable yet).
                    if (!link.startsWith("http")) return@mapIndexedNotNull null

                    Video.Server(
                        id = "$SERVER_ID_PREFIX$index",
                        name = buildLabel(service, s),
                        src = link,
                    )
                }
                Log.i(TAG, "Debrid added ${servers.size} server(s) for $imdbId")
                servers
            }
        } catch (e: Exception) {
            Log.e(TAG, "Debrid lookup failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Resolve one of our debrid servers into a playable Video.
     * The direct link is already in `server.src`, so this is trivial — but the
     * player still routes every server through getVideo(), so we implement it.
     */
    suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        Video(source = server.src)
    }

    /**
     * Apply debrid configuration pushed from your panel (operator-provided key).
     * Only non-null / non-blank values overwrite what's stored.
     */
    fun applyRemoteConfig(service: String?, apiKey: String?, torrentioBaseUrl: String? = null) {
        if (!service.isNullOrBlank()) UserPreferences.debridService = service.lowercase()
        if (apiKey != null) UserPreferences.debridApiKey = apiKey
        if (!torrentioBaseUrl.isNullOrBlank()) UserPreferences.torrentioBaseUrl = torrentioBaseUrl
    }

    /** A short, readable label like "[PM] 1080p 2.1 GB". */
    private fun buildLabel(service: String, s: TorrentioStream): String {
        val tag = when (service) {
            "premiumize" -> "PM"
            "realdebrid" -> "RD"
            "alldebrid" -> "AD"
            else -> service.uppercase()
        }
        val haystack = "${s.name.orEmpty()} ${s.title.orEmpty()}"
        val quality = Regex("\\b(2160p|4K|1080p|720p|480p|360p)\\b", RegexOption.IGNORE_CASE)
            .find(haystack)?.value?.uppercase() ?: ""
        val size = Regex("([0-9]+(?:\\.[0-9]+)?\\s?(?:GB|MB))", RegexOption.IGNORE_CASE)
            .find(s.title.orEmpty())?.value ?: ""
        return listOf("[$tag]", quality, size)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "[$tag] Debrid" }
    }

    private data class TorrentioResponse(
        @SerializedName("streams") val streams: List<TorrentioStream>?,
    )

    private data class TorrentioStream(
        @SerializedName("name") val name: String?,
        @SerializedName("title") val title: String?,
        @SerializedName("url") val url: String?,
    )
}
