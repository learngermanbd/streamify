package com.streamify.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Android 16 (API 36) migration · v1.1.0 — security-related preference
 * flags persisted in a small DataStore.
 *
 * Currently holds one boolean [HAS_SEEN_V11_REAUTH]: set to `true` the
 * first time the user has been shown the "please sign in again"
 * explanation in
 * [com.streamify.app.ui.activities.MainActivity.onCreate].
 *
 * ## Why DataStore (not SharedPreferences / EncryptedSharedPreferences)?
 *
 * The token-storage rationale in [com.streamify.app.security.TokenManager]
 * applies here too — `androidx.security:security-crypto` 1.1.0-alpha06
 * was removed in the A16 migration because it can throw
 * `KeyStoreException` unpredictably on Android 16's stricter keystore
 * key-commitment rules. This MonoFlag is not security-sensitive (its
 * only consumer is the in-app UX cue telling the user to re-auth),
 * so plain `streamify_security` DataStore is appropriate.
 *
 * DataStore (rather than raw SharedPreferences) matches the
 * convention established by [ThemePrefs], [UpdatePrefs] and
 * [com.streamify.app.data.prefs.PlayerPrefs] so the migration code
 * stays consistent with the rest of the codebase.
 */
val Context.securityPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamify_security"
)

class SecurityPrefs(private val appContext: Context) {

    private val ds get() = appContext.securityPrefsDataStore

    /**
     * Hot Flow — call sites already inside a coroutine scope
     * (`lifecycleScope.launch` in MainActivity) prefer this so the
     * DataStore read doesn't need to be wrapped in `runBlocking`.
     */
    val hasSeenV11ReauthFlow: Flow<Boolean> =
        ds.data.map { it[HAS_SEEN_V11_REAUTH] ?: false }

    /**
     * One-shot snapshot read for synchronous init paths
     * (`runBlocking { ... }` from `StreamifyApp.onCreate`).
     * Returns `false` when the preference has never been written.
     */
    suspend fun hasSeenV11Reauth(): Boolean =
        ds.data.first()[HAS_SEEN_V11_REAUTH] ?: false

    /** Persist that the user has been shown the v1.1.0 re-auth note. */
    suspend fun markV11ReauthSeen() {
        ds.edit { prefs ->
            prefs[HAS_SEEN_V11_REAUTH] = true
        }
    }

    companion object {
        private val HAS_SEEN_V11_REAUTH =
            booleanPreferencesKey("has_seen_v11_reauth")
    }
}
