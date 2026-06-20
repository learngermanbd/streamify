package com.streamify.app.data.prefs

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Phase 6 · Step 6.4 — User-controllable theme preference
 * (System / Light / Dark).
 *
 * Persists in a tiny DataStore so the value can be consumed at
 * [com.streamify.app.StreamifyApp.onCreate] BEFORE any Activity
 * inflates. That guarantees the first Activity launched after a cold
 * start already holds the right colour palette — no dark→light flash
 * on launch.
 *
 * The persisted token maps directly into
 * [AppCompatDelegate.setDefaultNightMode]'s constants via
 * [toNightModeFlag]. Splits this out from [PlayerPrefs] because the
 * Decision of MODE_NIGHT_X has to land during
 * [com.streamify.app.StreamifyApp.onCreate] (process-wide), before
 * PlayerPrefs is constructed (PlayerPrefs is wired through the
 * `lazy network` seam).
 */
val Context.themePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_prefs"
)

class ThemePrefs(private val context: Context) {

    /**
     * The user-selectable surface modes. [SYSTEM] is the default — it
     * tracks the device Configuration.uiMode so the app flips between
     * light and dark alongside the OS without further user input.
     * [LIGHT]/[DARK] pin the theme to one side.
     *
     * The string values are stable \u2014 they're the on-disk record. Reorder
     * the enum freely; the disk format won't break.
     */
    enum class ThemeMode(val storageKey: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark");

        companion object {
            /** Resilient against hand-edited DataStore blobs / schema drift. */
            fun fromStorageKey(key: String?): ThemeMode =
                values().firstOrNull { it.storageKey == key } ?: SYSTEM
        }
    }

    /** Hot flow for callers that want live updates (currently unused \u2014
     *  the Settings UI reads with a one-shot [currentMode]). */
    val themeModeFlow: Flow<ThemeMode>
        get() = context.themePrefsDataStore.data.map { prefs ->
            ThemeMode.fromStorageKey(prefs[KEY_THEME_MODE])
        }

    /**
     * One-shot read used at app startup. Suspend-friendly so callers
     * can `runBlocking { ... }` it from
     * [com.streamify.app.StreamifyApp.onCreate] without spinning a
     * structured-concurrency scope just to resolve a few bytes.
     */
    suspend fun currentMode(): ThemeMode = themeModeFlow.first()

    /** Persist + propagate via [AppCompatDelegate] at the call site. */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.storageKey
        }
    }

    /**
     * Pure mapper to AppCompatDelegate's expected constant. Kept on
     * [ThemePrefs] (not on [ThemeMode]) so the dependency on
     * [AppCompatDelegate] doesn't leak into the data layer's enum.
     */
    fun toNightModeFlag(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK   -> AppCompatDelegate.MODE_NIGHT_YES
    }

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
