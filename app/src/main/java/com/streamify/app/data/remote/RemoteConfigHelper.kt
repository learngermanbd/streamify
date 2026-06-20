package com.streamify.app.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.streamify.app.StreamifyApp.Companion.remoteConfigDataStore
import com.streamify.app.security.RuntimeStringProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Fetches the admin-controlled `GET /api/config` payload and caches it in DataStore
 * Preferences for offline fallback. Refresh interval: 30 minutes by default.
 *
 * Phase 1 \u00b7 Step 1.4. The hostname is a placeholder; real admin DNS lands in Phase 8.
 */
object RemoteConfigHelper {

    /**
     * Phase 7 · Step 7.2 — Config endpoint URL, decrypted at runtime
     * from build-time encrypted constants.  Replaces the old
     * `private const val DEFAULT_API_URL` plaintext literal.
     */
    private fun defaultApiUrl(): String = RuntimeStringProvider.getString("API_CONFIG_URL")

    /** 30 minutes in milliseconds. */
    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L

    /** X-App-Version header sent on every fetch. Read via BuildConfig.VERSION_NAME in 6.5. */
    private const val APP_VERSION = "1.0.0"

    private val CACHE_KEY = stringPreferencesKey("cached_config_json")
    private val CACHE_TIMESTAMP_KEY = stringPreferencesKey("cached_config_timestamp")

    /**
     * Fetch the latest [AppConfig]. Returns the cached copy if it's still fresh and we
     * aren't [force]-refreshing. Falls back to [AppConfig.defaults] on network failure
     * AND no cache.
     *
     * Call from a coroutine scope. Runs entirely on `Dispatchers.IO`.
     */
    suspend fun fetchConfig(
        context: Context,
        force: Boolean = false,
        httpClient: OkHttpClient
    ): AppConfig = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = readCache(context)

        if (!force && cached != null) {
            val lastFetch = readCacheTimestamp(context) ?: 0L
            if ((now - lastFetch) < REFRESH_INTERVAL_MS) {
                return@withContext cached
            }
        }

        try {
            val request = Request.Builder()
                .url(defaultApiUrl())
                .header("X-App-Version", APP_VERSION)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    returnCacheOrDefaults(context, cached)
                } else {
                    val parsed = parseAppConfig(JSONObject(body))
                    writeCache(context, body, now)
                    parsed
                }
            }
        } catch (e: IOException) {
            // Network failure: prefer cache over hard defaults.
            returnCacheOrDefaults(context, cached)
        } catch (e: CancellationException) {
            throw e // structured concurrency — never swallow
        } catch (e: Exception) {
            // Parsing failure: same fallback.
            returnCacheOrDefaults(context, cached)
        }
    }

    private suspend fun returnCacheOrDefaults(context: Context, cached: AppConfig?): AppConfig {
        return cached ?: AppConfig.defaults().also {
            // Cache defaults so the next fetch attempts the network at least once.
            writeCacheTimestamp(context, 0L)
        }
    }

    private suspend fun readCache(context: Context): AppConfig? {
        val raw = context.remoteConfigDataStore.data.first()[CACHE_KEY] ?: return null
        return runCatching { parseAppConfig(JSONObject(raw)) }.getOrNull()
    }

    private suspend fun readCacheTimestamp(context: Context): Long? {
        val raw = context.remoteConfigDataStore.data.first()[CACHE_TIMESTAMP_KEY]
        return raw?.toLongOrNull()
    }

    private suspend fun writeCache(context: Context, json: String, timestamp: Long) {
        context.remoteConfigDataStore.edit { prefs ->
            prefs[CACHE_KEY] = json
            prefs[CACHE_TIMESTAMP_KEY] = timestamp.toString()
        }
    }

    private suspend fun writeCacheTimestamp(context: Context, timestamp: Long) {
        context.remoteConfigDataStore.edit { prefs ->
            prefs[CACHE_TIMESTAMP_KEY] = timestamp.toString()
        }
    }

    private fun parseAppConfig(json: JSONObject): AppConfig {
        val flagsObj = json.optJSONObject("featureFlags")
        val featureFlags = if (flagsObj != null) {
            buildMap(flagsObj.length()) {
                val keys = flagsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, flagsObj.optBoolean(k, false))
                }
            }
        } else {
            emptyMap()
        }
        // Phase 6 · Step 6.2 — back-compat fallback: if the server
        // omits `latestVersion` (Phase 1.x payloads), inherit from
        // `minAppVersion` so the optional-update branch is still well-
        // defined. Missing `minAppVersion` itself falls back to the
        // local app version so the Floor doesn't trigger a forced
        // upgrade against an absent field.
        val min = json.optString("minAppVersion", APP_VERSION)
        val latest = json.optString("latestVersion", min)
        return AppConfig(
            apiBaseUrl      = json.optString("apiBaseUrl", ""),
            updateUrl       = json.optString("updateUrl", ""),
            latestVersion   = latest,
            telegramLink    = json.optString("telegramLink", ""),
            noticeText      = json.optString("noticeText", ""),
            maintenanceMode = json.optBoolean("maintenanceMode", false),
            minAppVersion   = min,
            featureFlags    = featureFlags
        )
    }
}
