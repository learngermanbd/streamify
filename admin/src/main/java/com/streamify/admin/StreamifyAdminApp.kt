package com.streamify.admin

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StreamifyAdminApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Default uncaught-exception handler — log every uncaught Throwable
        // with a stable tag so a future crash is easy to capture from logcat:
        //   adb logcat -s StreamifyAdminApp.Crash AndroidRuntime:E *:S
        // We still delegate to the previously-installed handler so the OS /
        // Crashlytics still receive the exception (crash dialog, ANR, etc.).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // post-v1.1.0 fix — bound the full call budget so a wedged
            // body read after connect can't blow past the sum of phases
            // and pin the UI on the loading spinner past 60 s.
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** In-memory JWT token. Set after successful login. */
    var adminToken: String = ""
    var adminRefreshToken: String = ""
    var adminName: String = "Admin"
    var adminRole: String = "EDITOR"

    /**
     * post-v1.1.0 — Process-scoped [CoroutineScope] used for background
     * write operations that MUST complete even if the originating
     * Activity is destroyed mid-flight (e.g. the user hits Logout then
     * instantly kills the app from recent-apps). Without this we risk
     * a half-written DataStore that leaves a stale token on disk.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    companion object {
        const val ADMIN_API_BASE_URL = "https://learngermanwith.fun"

        /** Stable logcat tag used by [StreamifyAdminApp]'s default uncaught-exception handler. */
        const val TAG = "StreamifyAdminApp.Crash"

        val Context.adminTokenStore by preferencesDataStore(
            name = "streamify_admin_tokens"
        )

        // post-v1.1.0 — DataStore-backed credentials for auto-login across
        // process restarts. Keys mirror the in-memory [adminToken] family
        // so the load path keeps the two in sync.
        val KEY_ACCESS_TOKEN  = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_NAME          = stringPreferencesKey("name")
        val KEY_ROLE          = stringPreferencesKey("role")
    }

    /**
     * post-v1.1.0 fix — Minimal auth-state snapshot. Persisted to the
     * already-declared-but-unused [adminTokenStore] DataStore so admins
     * do not have to re-login on every cold launch. The previously
     * in-memory-only `var adminToken / adminRefreshToken / adminName /
     * adminRole` quartet was lost on every process death.
     */
    data class AdminSession(
        val accessToken: String,
        val refreshToken: String,
        val name: String,
        val role: String
    )

    /**
     * Persist the post-login session. Updates the in-memory fields
     * SYNCHRONOUSLY first so the immediately-following `startActivity`
     * to [DashboardActivity] sees the new token without waiting for
     * the disk write — and only after that does the DataStore write
     * begin. Cheap to call; safe across process death.
     */
    suspend fun saveSession(session: AdminSession) {
        adminToken       = session.accessToken
        adminRefreshToken = session.refreshToken
        adminName        = session.name
        adminRole        = session.role
        adminTokenStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN]  = session.accessToken
            prefs[KEY_REFRESH_TOKEN] = session.refreshToken
            prefs[KEY_NAME]          = session.name
            prefs[KEY_ROLE]          = session.role
        }
    }

    /**
     * One-shot read of the persisted session (if present). Mirrors the
     * session into the in-memory fields used by [AdminApi]'s
     * `tokenProvider` lambda so a freshly-created [DashboardActivity]
     * after a cold-restart can authorise requests without forcing a
     * re-login. Returns `null` when no token is persisted.
     */
    suspend fun loadSession(): AdminSession? {
        val prefs = adminTokenStore.data.first()
        val token = prefs[KEY_ACCESS_TOKEN] ?: return null
        if (token.isBlank()) return null
        val session = AdminSession(
            accessToken   = token,
            refreshToken  = prefs[KEY_REFRESH_TOKEN].orEmpty(),
            name          = prefs[KEY_NAME] ?: "Admin",
            role          = prefs[KEY_ROLE] ?: "EDITOR"
        )
        adminToken       = session.accessToken
        adminRefreshToken = session.refreshToken
        adminName        = session.name
        adminRole        = session.role
        return session
    }

    /**
     * post-v1.1.0 fix — Logout now persists across restart. Resets the
     * in-memory fields immediately so any in-flight AdminApi request
     * using the now-stale token completes (or fails) but no future
     * request will see the cleared token — and the DataStore entries
     * are wiped so auto-login does not bring them back.
     *
     * Wrapped in [NonCancellable] so an Activity kill mid-logout does
     * not leave the DataStore half-written. The user-cancellation cost
     * is acceptable because logout is a deliberate user action and the
     * operation is bounded (<10 ms in practice).
     */
    suspend fun clearSession() = withContext(NonCancellable) {
        adminToken        = ""
        adminRefreshToken = ""
        adminName         = "Admin"
        adminRole         = "EDITOR"
        adminTokenStore.edit { prefs ->
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_NAME)
            prefs.remove(KEY_ROLE)
        }
        Unit
    }

    /**
     * Convenience fire-and-forget wrapper used by Activity logout handlers
     * so the UI can navigate eagerly (see DashboardActivity.logout) while
     * the DataStore write runs on [appScope] and is immune to Activity
     * destruction.
     */
    fun clearSessionInBackground() {
        appScope.launch { clearSession() }
    }
}
