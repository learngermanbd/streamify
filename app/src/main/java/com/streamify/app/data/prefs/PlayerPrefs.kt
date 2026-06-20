package com.streamify.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamify.app.data.models.RecentStreamUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Step 4.5 (+ Step 4.6 overlay position + Step 5.3 recent URLs + Step 5.6
 * recent searches) — DataStore-backed preferences for player settings.
 *
 * Keys:
 *  - KEY_VIDEO_QUALITY            ("video_quality_mode")      → [VideoQualityMode.name]
 *  - KEY_SUBTITLE_LANG            ("subtitle_track_id")       → opaque track id
 *  - KEY_FLOATING_X_RATIO         ("floating_player_x_ratio") → 0.0..1.0
 *  - KEY_FLOATING_Y_RATIO         ("floating_player_y_ratio") → 0.0..1.0
 *  - KEY_RECENT_NETWORK_URLS      ("recent_network_urls")     → JSON list of objects
 *  - KEY_RECENT_SEARCHES          ("recent_searches")         → JSON list of strings
 *
 * Scope: GLOBAL. Reads are suspend (use [first] for one-shot reads);
 * writes use [edit].
 */
val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(name = "player_prefs")

class PlayerPrefs(private val context: Context) {

    /** Latest persisted video quality. Defaults to [VideoQualityMode.AUTO]. */
    val videoQualityModeFlow: Flow<VideoQualityMode>
        get() = context.playerDataStore.data.map { prefs ->
            val saved = prefs[KEY_VIDEO_QUALITY]
            VideoQualityMode.fromStorageKey(saved) ?: VideoQualityMode.AUTO
        }

    /** Latest persisted subtitle track id (or empty/Off). */
    val subtitleTrackIdFlow: Flow<String>
        get() = context.playerDataStore.data.map { prefs ->
            prefs[KEY_SUBTITLE_LANG].orEmpty()
        }

    /**
     * Step 4.6 — Floating overlay horizontal position ratio (0.0 = left
     * edge, 1.0 = right edge). Default 0.95 = top-right corner with
     * comfortable edge inset on portrait phones.
     */
    val floatingPlayerXRatioFlow: Flow<Float>
        get() = context.playerDataStore.data.map { prefs ->
            prefs[KEY_FLOATING_X_RATIO] ?: DEFAULT_FLOATING_X_RATIO
        }

    /** Step 4.6 — Floating overlay vertical position ratio (0.0 = top
     *  edge, 1.0 = bottom edge). Default 0.05. */
    val floatingPlayerYRatioFlow: Flow<Float>
        get() = context.playerDataStore.data.map { prefs ->
            prefs[KEY_FLOATING_Y_RATIO] ?: DEFAULT_FLOATING_Y_RATIO
        }

    suspend fun setVideoQualityMode(mode: VideoQualityMode) {
        context.playerDataStore.edit { prefs ->
            prefs[KEY_VIDEO_QUALITY] = mode.storageKey
        }
    }

    suspend fun setSubtitleTrackId(id: String) {
        context.playerDataStore.edit { prefs ->
            prefs[KEY_SUBTITLE_LANG] = id
        }
    }

    /** Step 4.6 — Persist floating overlay position; both ratios clamped
     *  0f..1f by the caller (FloatingPlayerService snap-to-edge math). */
    suspend fun setFloatingPlayerPosition(xRatio: Float, yRatio: Float) {
        context.playerDataStore.edit { prefs ->
            prefs[KEY_FLOATING_X_RATIO] = xRatio.coerceIn(0f, 1f)
            prefs[KEY_FLOATING_Y_RATIO] = yRatio.coerceIn(0f, 1f)
        }
    }

    // ----- Phase 5 · Step 5.3 — Recent network URLs -----

    /**
     * Flow of the most-recent [MAX_RECENT_URLS] streams the user opened.
     * Backed by a JSON-encoded string preference so we can carry the
     * user's per-URL [RecentStreamUrl.formatLabel] choice (info-only —
     * Media3 auto-detects the actual playback format).
     */
    val recentNetworkUrlsFlow: Flow<List<RecentStreamUrl>>
        get() = context.playerDataStore.data.map { prefs ->
            decodeRecentUrls(prefs[KEY_RECENT_NETWORK_URLS])
        }

    /**
     * Add (or move-to-front) a network URL. De-duplicates by URL
     * (case-insensitive — matches [removeRecentNetworkUrl] semantics),
     * then prepends so the freshest entry is at the top of the list.
     * Caps to [MAX_RECENT_URLS] by trimming the oldest (last) entry.
     */
    suspend fun addRecentNetworkUrl(url: String, formatLabel: String) {
        val cleaned = url.trim()
        if (cleaned.isBlank()) return
        context.playerDataStore.edit { prefs ->
            val current = decodeRecentUrls(prefs[KEY_RECENT_NETWORK_URLS]).toMutableList()
            current.removeAll { it.url.equals(cleaned, ignoreCase = true) }
            current.add(
                0,
                RecentStreamUrl(
                    url = cleaned,
                    formatLabel = formatLabel.ifBlank { RecentStreamUrl.FORMAT_AUTO },
                    addedAtMs = System.currentTimeMillis()
                )
            )
            while (current.size > MAX_RECENT_URLS) current.removeAt(current.size - 1)
            prefs[KEY_RECENT_NETWORK_URLS] = encodeRecentUrls(current)
        }
    }

    /**
     * Remove a recent URL (e.g. on user long-press delete). Lookup is
     * case-insensitive to mirror [addRecentNetworkUrl]'s de-dup semantics
     * — a URL saved as `Https://live.foo/playlist.m3u8` must still be
     * reachable here regardless of how the user types it on tap.
     */
    suspend fun removeRecentNetworkUrl(url: String) {
        context.playerDataStore.edit { prefs ->
            val current = decodeRecentUrls(prefs[KEY_RECENT_NETWORK_URLS]).toMutableList()
            val removedAny = current.removeAll { it.url.equals(url, ignoreCase = true) }
            if (removedAny) prefs[KEY_RECENT_NETWORK_URLS] = encodeRecentUrls(current)
        }
    }

    /**
     * JSON list decoder. Re-throws CancellationException so a Flow
     * collection cancellation mid-iteration doesn't silently fall back
     * to an empty list. Malformed JSON returns empty list (defensive
     * against hand-edited DataStore blobs / schema drift).
     */
    private fun decodeRecentUrls(raw: String?): List<RecentStreamUrl> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching {
                RecentStreamUrl.fromJson(arr.getJSONObject(i))
            }.getOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun encodeRecentUrls(list: List<RecentStreamUrl>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    // ----- Phase 5 · Step 5.6 — Recent search queries -----

    /**
     * Most-recent [MAX_RECENT_SEARCHES] queries the user has submitted
     * from the Search screen. Plain strings (no URL / format metadata)
     * so the JSON-encoded list is `["foo","bar"]` rather than a list of
     * objects. Mirror of the recent-URL flow above; same DataStore.
     */
    val recentSearchesFlow: Flow<List<String>>
        get() = context.playerDataStore.data.map { prefs ->
            decodeRecentSearches(prefs[KEY_RECENT_SEARCHES])
        }

    /**
     * Add (or move-to-front) a query. De-duplicates case-insensitively
     * (parallels [addRecentNetworkUrl]'s scheme so users who re-type
     * `"liverpool"` after a previous `"Liverpool"` hit don't see a
     * duplicate chip). Blank input is a no-op so the chip strip doesn't
     * grow an empty placeholder.
     */
    suspend fun addRecentSearch(query: String) {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return
        context.playerDataStore.edit { prefs ->
            val current = decodeRecentSearches(prefs[KEY_RECENT_SEARCHES]).toMutableList()
            current.removeAll { it.equals(cleaned, ignoreCase = true) }
            current.add(0, cleaned)
            while (current.size > MAX_RECENT_SEARCHES) current.removeAt(current.size - 1)
            prefs[KEY_RECENT_SEARCHES] = encodeRecentSearches(current)
        }
    }

    /**
     * Remove a recent search (e.g. on chip close-icon tap). Lookup is
     * case-insensitive to mirror [addRecentSearch]'s de-dup semantics.
     */
    suspend fun removeRecentSearch(query: String) {
        context.playerDataStore.edit { prefs ->
            val current = decodeRecentSearches(prefs[KEY_RECENT_SEARCHES]).toMutableList()
            val removedAny = current.removeAll { it.equals(query, ignoreCase = true) }
            if (removedAny) prefs[KEY_RECENT_SEARCHES] = encodeRecentSearches(current)
        }
    }

    private fun decodeRecentSearches(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getString(i) }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun encodeRecentSearches(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    /** One-shot read for the startup-apply step. */
    suspend fun readVideoQualityMode(): VideoQualityMode = videoQualityModeFlow.first()
    suspend fun readSubtitleTrackId(): String = subtitleTrackIdFlow.first()

    companion object {
        val KEY_VIDEO_QUALITY = stringPreferencesKey("video_quality_mode")
        val KEY_SUBTITLE_LANG = stringPreferencesKey("subtitle_track_id")
        // Step 4.6 — floating overlay position.
        val KEY_FLOATING_X_RATIO = floatPreferencesKey("floating_player_x_ratio")
        val KEY_FLOATING_Y_RATIO = floatPreferencesKey("floating_player_y_ratio")
        // Phase 5 · Step 5.3 — recent network URLs (JSON-encoded).
        val KEY_RECENT_NETWORK_URLS = stringPreferencesKey("recent_network_urls")

        // Phase 5 · Step 5.6 — recent search queries (JSON-encoded list of strings).
        val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")

        const val DEFAULT_FLOATING_X_RATIO = 0.95f
        const val DEFAULT_FLOATING_Y_RATIO = 0.05f

        /** Phase 5 · Step 5.3 — Cap on the recent-URL list. */
        const val MAX_RECENT_URLS = 10

        /** Phase 5 · Step 5.6 — Cap on the recent-search chip strip. */
        const val MAX_RECENT_SEARCHES = 8
    }
}

/**
 * Resolution ladder for [VideoQualityManager].
 *
 *  - [AUTO]  — leave Media3 adaptive selection enabled.
 *  - [FHD]   — ≤ 1080p and > 720p track wins if present.
 *  - [HD]    — ≤ 720p  and > 480p track wins if present.
 *  - [SD]    — ≤ 480p  and > 360p track wins if present.
 *  - [LD]    — ≤ 360p  track wins if present.
 */
enum class VideoQualityMode(val storageKey: String, val maxHeightInclusive: Int) {
    AUTO("auto", Int.MAX_VALUE),
    FHD("fhd", 1080),
    HD("hd", 720),
    SD("sd", 480),
    LD("ld", 360);

    companion object {
        fun fromStorageKey(key: String?): VideoQualityMode? = values().firstOrNull { it.storageKey == key }
    }
}
