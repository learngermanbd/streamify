package com.streamify.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Phase 6 · Step 6.2 — DataStore wrapper for update-related preferences.
 *
 * Two keys are persisted:
 *  - [DISMISSED_LATEST] — the version the user tapped "Later" on. We
 *    won't surface that optional-update nag again until the server bumps
 *    `latestVersion` to something the user hasn't dismissed yet.
 *  - [LAST_FORCED] — the last forced minVersion we routed the user through
 *    UpdateActivity for. Currently used only for Sentry breadcrumbs
 *    (Phase 7 hooks); not blocking.
 *
 * `updatePrefs` extension lives on Context (one instance per process).
 * `MutablePreferences.edit { ... }` is null-safe — if the underlying
 * preference is missing, `it[key] = value` inserts.
 */
private val Context.updatePrefsDataStore by preferencesDataStore(name = "streamify_updates")

class UpdatePrefs(private val appContext: Context) {

    private val ds get() = appContext.updatePrefsDataStore

    val dismissedLatestFlow: Flow<String?> =
        ds.data.map { it[DISMISSED_LATEST] }

    val lastForcedFlow: Flow<String?> =
        ds.data.map { it[LAST_FORCED] }

    /**
     * Snapshot read for the splash gate — the splash can't await a Flow
     * without spinning a coroutine, so it asks for the current value.
     */
    suspend fun readDismissedLatest(): String? =
        ds.data.first()[DISMISSED_LATEST]

    suspend fun readLastForced(): String? =
        ds.data.first()[LAST_FORCED]

    suspend fun dismissOptional(latest: String) {
        ds.edit { prefs ->
            prefs[DISMISSED_LATEST] = latest
        }
    }

    suspend fun rememberForced(min: String) {
        ds.edit { prefs ->
            prefs[LAST_FORCED] = min
        }
    }

    companion object {
        private val DISMISSED_LATEST =
            stringPreferencesKey("dismissed_latest_version")
        private val LAST_FORCED =
            stringPreferencesKey("last_forced_min_version")
    }
}
