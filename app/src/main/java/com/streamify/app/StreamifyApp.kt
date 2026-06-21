package com.streamify.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.preferencesDataStore
import com.streamify.app.BuildConfig
import com.streamify.app.data.crash.CrashHandler
import com.streamify.app.data.local.LocalModule
import com.streamify.app.data.prefs.ThemePrefs
import com.streamify.app.data.prefs.UpdatePrefs
import com.streamify.app.data.remote.AppConfig
import com.streamify.app.data.remote.NetworkModule
import com.streamify.app.data.remote.RemoteConfigHelper
import com.streamify.app.data.repository.RepositoryModule
import com.streamify.app.data.update.AppUpdateManager
import com.streamify.app.security.HoneyPotManager
import com.streamify.app.security.SecurityGate
import com.streamify.app.security.SecurityModule
import com.streamify.app.services.UpdateWorker
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.runBlocking

/**
 * Application entry-point. Initialised once per process.
 *
 * Phase 0 ✓ — environment wired (JDK 17 · Android SDK 35 · Gradle 8.11.1).
 * Phase 1 ✓ — Project scaffold, dependencies, theme, FCM+Sentry init.
 * Phase 2 ✓ — Network + Local + Repository DI seams.
 * Phase 3 ✓ — Activities + Fragments + Adapters + UI flows.
 * Phase 4 ✓ — ExoPlayer + PiP + gestures + SubTitle/Quality selectors.
 * Phase 5 ✓ — Favorites + Playlists + Network Stream + Push + Notice v2 + Search.
 * Phase 6 · Step 6.2 — exposes [updateManager] + [updatePrefs] for the
 *   auto-update flow. Phase 6.2 review-pass MAJOR: `network`, `local`,
 *   `repository` are now LAZY (replacing the previous `lateinit var` form)
 *   so the [com.streamify.app.services.UpdateDownloadReceiver] does
 *   NOT race [Application.onCreate] when a queued
 *   `ACTION_DOWNLOAD_COMPLETE` broadcast fires before the assignment runs.
 */
class StreamifyApp : Application() {

    /**
     * Phase 6 · Step 6.5 — Sentry first: captures initialization crashes
     * of downstream steps. DSN is injected from [BuildConfig.SENTRY_DSN]
     * (populated by `app/build.gradle.kts` from
     * `signing.properties → APP_SENTRY_DSN`). When blank (debug install
     * without prod creds) the SDK no-ops and falls through to Logcat's
     * native stack trace. We deliberately do NOT throw — `assembleRelease`
     * should still succeed with no `signing.properties` present so that
     * minification pipeline tests don't have to drop credentials.
     *
     * Crash-only reporting tuned for v1:
     *   sampleRate        = 1.0   — always capture the crash or it never
     *                              ships; Sentry's default for `error`
     *                              events is already 1.0 but we set it
     *                              explicitly so a future SDK change
     *                              can't silently suppress crashes.
     *   tracesSampleRate  = 0.0   — no perf spans (saves Sentry quota +
     *                              avoids the player-overlay frame
     *                              ingestion tax on ExoPlayer sessions).
     *   sessionTracking   = !DEBUG — release builds don't tap into
     *                              background telemetry the user has
     *                              already opted out of at OS level.
     *   attachStacktrace  = true
     *   attachThreads     = true  — multi-thread crashes (the most      *                              common shape in this codebase — FCM
     *                              + ExoPlayer render thread + DataStore
     *                              IO) need the full thread dump.
     *   attachViewHierarchy = false  — PiP overlay + crash-screen pixels
     *                              would leak user state; we skip it.
     */
    private fun initSentry() {
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN.takeIf { it.isNotBlank() }
            options.isDebug = BuildConfig.DEBUG
            options.sampleRate = 1.0
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = !BuildConfig.DEBUG
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.isAttachStacktrace = true
            options.isAttachThreads = true
            options.isAttachViewHierarchy = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrubPii(event) }
            options.setTag("versionName", BuildConfig.VERSION_NAME)
            options.setTag("versionCode", BuildConfig.VERSION_CODE.toString())
            options.setTag("process", "main")
        }
    }

    /**
     * Phase 6 · Step 6.5 — in-place PII scrub for any event Sentry is about
     * to capture. Strips Bearer/JWT tokens, FCM tokens (≥140 chars that
     * look like firebase registration tokens), Android-IDs and cookie
     * values from message text, breadcrumb data + HTTP request headers.
     * We never drop the event (returning null would silence the crash)
     * — we ALWAYS scrub in place and let the report ship.
     */
    private fun scrubPii(event: SentryEvent): SentryEvent? {
        val piiPatterns = listOf(
            // RFC 6750 Bearer scheme + RFC 7519 base64url chars
            Regex("(?i)(Bearer\\s+)[A-Za-z0-9\\-._~+/]+=*"),
            // FCM registration tokens (140+ char base64)
            Regex("(?i)(FCM[_-]?Token[:= ]?)[A-Za-z0-9_\\-]{140,}"),
            // Advertising-ID / ANDROID_ID style hex
            Regex("(?i)(android[_-]?id[:= ]?)[A-Fa-f0-9\\-]{8,}"),
            // Cookie header value
            Regex("(?i)(Cookie[:= ]?)[^\\s;]+"),
        )
        fun redact(input: String?): String? {
            if (input.isNullOrBlank()) return input
            var scrubbed: String = input
            piiPatterns.forEach { scrubbed = scrubbed.replace(it, "$1[Filtered]") }
            return scrubbed
        }
        event.message?.message?.let { event.message?.message = redact(it) }
        event.breadcrumbs?.forEach { bc ->
            bc.message?.let { bc.message = redact(it) }
            bc.data?.keys?.toList()?.forEach { k ->
                bc.data?.get(k)?.let { v ->
                    bc.data?.put(k, redact(v?.toString()) ?: v)
                }
            }
        }
        event.request?.headers?.let { headers ->
            headers.keys.toList().forEach { k ->
                if (k.equals("Authorization", true) ||
                    k.contains("Token", true) ||
                    k.contains("Cookie", true)
                ) {
                    headers[k] = "[Filtered]"
                }
            }
        }
        return event
    }

    override fun onCreate() {
        super.onCreate()

        // -----------------------------------------------------------------
        // Phase 6 · Step 6.4 — CrashHandler MUST be installed as the FIRST
        // non-trivial step.  Any subsequent failure (initSentry, BuildConfig
        // read, DataStore write, R8-stripped class lookup, etc.) lands in
        // CrashActivity instead of killing the process with no recovery
        // surface — this converts the user-visible "auto-closing" symptom
        // into a real error screen they can React to.  Wrapped in
        // try/catch because we cannot tolerate CrashHandler itself
        // throwing — that would leave the app with no recovery path.
        //
        // Ordering note (the previous flow installed AFTER Sentry).  The
        // chained order is now: Sentry (installed by initSentry below) →
        // our CrashHandler → Android KillApplicationHandler.  Sentry's
        // handler calls `previous.uncaughtException` which still routes
        // to us, so the on-disk dump + CrashActivity launch still
        // happens — and the Sentry capture happens first, which is what
        // we actually want for a release build.
        // -----------------------------------------------------------------
        try {
            CrashHandler.install(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "CrashHandler install failed; continuing without recovery UI", t)
        }

        // Phase 6 · Step 6.4 — early-return guard for the isolated
        // `:crash` process (AndroidManifest's `android:process=":crash"`
        // on CrashActivity). When the dying main process's handler
        // forks the recovery Activity, a new Application instance is
        // created in the `:crash` process. We must skip the heavy
        // initialisation so we don't duplicate Sentry telemetry or
        // accidentally fire any of the receivers/wires the dying main
        // process just tore down.
        try {
            if (android.os.Process.myProcessName().endsWith(":crash")) return
        } catch (t: Throwable) {
            Log.w(TAG, "processName check failed; assuming main process", t)
        }

        // -----------------------------------------------------------------
        // Defensive launch hardening — wrap every subsequent init step in
        // try/catch so a single failure cannot derail the whole launch.
        // Failures are LOGGED but do not abort; the app still proceeds to
        // SplashActivity so the user sees the existing error-card UI
        // (with Retry) rather than a silent process death.  Each `runStep`
        // rethrows CancellationException so structured concurrency survives.
        // -----------------------------------------------------------------
        runStep("initSentry") { initSentry() }

        // Phase 7 · Step 7.2 — initialise string-encryption subsystem.
        // Registers a ComponentCallbacks2 that wipes the decrypted-value
        // cache when the app enters the background (onTrimMemory).
        runStep("SecurityModule.init") { SecurityModule.init(this) }

        // We deliberately do NOT initialize Firebase here — FirebaseApp
        // auto-initializes from the `google-services.json` plugin and we
        // only rely on FirebaseMessaging (Phase 5 · Step 5.4).
        runStep("AppConfig.defaults (encrypted URL log)") {
            Log.i(TAG, "Resolved API Base URL: ${AppConfig.defaults().apiBaseUrl}")
        }

        // Phase 6 · Step 6.4 — apply persisted theme BEFORE any
        // Activity inflates so the first Activity launched after a
        // cold start holds the correct uiMode (no dark→light flash
        // on launch). DataStore.fist() returns from a tiny on-disk
        // blob (~10 ms typical, never blocks more than ~50 ms).
        //
        // NOTE: StrictMode disabled by default — enable for debug builds only
        // `detectDiskReads()` later, move this read into a
        // ContentProvider.onCreate (which fires before
        // Application.onCreate, before StrictMode policies activate
        // at attachBaseContext()). For v1 we accept the main-thread
        // DataStore read since no StrictMode policy is active.
        runStep("themePrefs apply") {
            val themeMode = runBlocking { themePrefs.currentMode() }
            AppCompatDelegate.setDefaultNightMode(themePrefs.toNightModeFlag(themeMode))
        }

        // Touch the lazy seams so onCreate's cold start still gets the
        // same observable behaviour as the previous lateinit version
        // (any FCM push that arrives during cold launch lands with the
        // cache + auth interceptor already wired). Lazy access in
        // onCreate is equivalent to the old explicit assignment but
        // doesn't expose an uninitialised-property window to the
        // UpdateDownloadReceiver.  We force-init each in its own
        // defensive step so an R8-stripped class lookup (or any other
        // Android 16-specific init issue) surfaces here — and gets
        // attributed to the right module — instead of crashing later
        // inside SplashActivity's ViewModel factory.
        runStep("NetworkModule init")   { network }
        runStep("LocalModule init")     { local }
        runStep("RepositoryModule init") { repository }

        // Phase 6 · Step 6.2 — schedule a daily background update check.
        // WorkManager coalesces duplicates via KEEP policy so this is
        // safe to call on every cold start.
        runStep("UpdateWorker.enqueue") { UpdateWorker.enqueue(this) }

        // Phase 7 · Step 7.6 — Security gate: risk-scoring orchestrator
        // that runs all security checks (integrity, tampering, root,
        // emulator, hooks) on a background thread.  Produces a combined
        // risk score that drives soft/hard/critical response via
        // SelfHealing gradual degradation.
        // Initialize honeypot canaries (Step 7.9)
        runStep("HoneyPotManager.init") { HoneyPotManager.init() }

        runStep("SecurityGate.runChecks") {
            SecurityGate.runChecks(applicationContext) { result ->
                Log.d(TAG, "Security gate: score=${result.score}, level=${result.level}")
            }
        }
    }

    /**
     * Phase 6 · Step 6.4 — Run a single launch step with defensive
     * guarding. Failures are LOGGED so Sentry (once `initSentry()` has
     * run) can pick them up, but the launch proceeds normally so the
     * user still reaches SplashActivity and sees the existing error
     * card UI (with Retry) instead of the process silently dying.
     *
     * `CancellationException` is rethrown so structured concurrency
     * survives — a coroutine cancellation in `runBlocking` must NOT be
     * silenced (it would mask lifecycle cancellation).
     */
    private fun runStep(stepName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Init step '$stepName' failed: ${t.javaClass.simpleName} ${t.message}", t)
        }
    }

    /**
     * Phase 6 · Step 6.2 — exposed DI seams are LAZY (replacing the
     * previous `lateinit var` form) so the [UpdateDownloadReceiver]
     * doesn't race [Application.onCreate]. First access initialises the
     * module; subsequent accesses reuse the same instance.
     */
    val network: NetworkModule by lazy { NetworkModule(this) }
    val local: LocalModule by lazy { LocalModule(this) }
    val repository: RepositoryModule by lazy {
        RepositoryModule(
            remoteDataSource = network.remoteDataSource,
            localDataSource = local.localDataSource,
            httpClient = network.httpClient,
            noticeDaoProvider = { local.noticeDao },
        )
    }

    /**
     * Phase 6 · Step 6.2 — Coordinator object for the auto-update
     * pipeline. Lazy; tied to the [NetworkModule.httpClient] so we
     * share the cache + auth interceptor stack.
     */
    val updateManager: AppUpdateManager by lazy {
        AppUpdateManager(applicationContext, network.httpClient)
    }

    /**
     * Phase 6 · Step 6.2 — Persisted storage for the "Last dismissed
     * optional update" guard. Lazy; cheap to construct because DataStore
     * wraps the file handler on first read.
     */
    val updatePrefs: UpdatePrefs by lazy {
        UpdatePrefs(applicationContext)
    }

    /**
     * Phase 6 · Step 6.4 — Persisted storage for the user's theme
     * preference (System / Light / Dark). Lazily constructed; the first
     * access happens during [onCreate] to push the stored uiMode into
     * [androidx.appcompat.app.AppCompatDelegate] BEFORE any Activity
     * inflates. The DataStore read (via [ThemePrefs.currentMode]) is
     * resolved with [runBlocking] from [onCreate] — acceptable
     * because the read returns from a tiny on-disk blob in a few ms.
     */
    val themePrefs: ThemePrefs by lazy {
        ThemePrefs(applicationContext)
    }

    /**
     * Android 16 (API 36) migration · v1.1.0 — Persisted storage for
     * security-related preference flags (currently just
     * `has_seen_v11_reauth`). Lazily constructed; read once at the
     * tail of [MainActivity.onCreate] so we can show the one-shot
     * "please sign in again" explanation to users whose encrypted
     * refresh tokens became unreadable when we dropped
     * `androidx.security:security-crypto`.
     */
    val securityPrefs: com.streamify.app.data.prefs.SecurityPrefs by lazy {
        com.streamify.app.data.prefs.SecurityPrefs(applicationContext)
    }

    /**
     * Android 16 (API 36) migration · v1.1.0 — Shared singleton
     * accessor for [com.streamify.app.security.TokenManager].  Exposed
     * here so MainActivity (and any future consumer) doesn't have to
     * construct their own; keeps the `context.getSharedPreferences`
     * wrapping in one place so the file name `\"streamify_auth\"` stays
     * consistent.  Lazy; first-access constructs the instance.
     */
    val tokenManager: com.streamify.app.security.TokenManager by lazy {
        com.streamify.app.security.TokenManager(applicationContext)
    }

    companion object {
        private const val TAG = "StreamifyApp"

        /**
         * Remote-config DataStore extension. Used by [RemoteConfigHelper]
         * to persist /api/config payloads across launches.
         */
        val Context.remoteConfigDataStore by preferencesDataStore(
            name = "streamify_remote_config"
        )

        // Sentry DSN flows from BuildConfig.SENTRY_DSN (populated by
        // app/build.gradle.kts from `signing.properties → APP_SENTRY_DSN`).
        // The companion's old `private const val SENTRY_DSN = ""` placeholder
        // is gone — BuildConfig is the single source of truth.
        // (Step 6.5 ships the `io.sentry.android.gradle` plugin alongside
        //  so `assembleRelease` auto-uploads mapping.txt for deobfuscated
        //  stack traces.)
    }
}
