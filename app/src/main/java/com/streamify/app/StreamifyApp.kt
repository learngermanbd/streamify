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
import com.streamify.app.security.IntegrityChecker
import com.streamify.app.security.RequestSigner
import com.streamify.app.security.SSLPinner
import com.streamify.app.security.SecretsValidator
import com.streamify.app.security.SecurityGate
import com.streamify.app.security.SecurityModule
import com.streamify.app.security.TokenStore
import com.streamify.app.services.UpdateWorker
import kotlinx.coroutines.runBlocking

/**
 * Application entry-point. Initialised once per process.
 *
 * Phase 0 ✓ — environment wired (JDK 17 · Android SDK 35 · Gradle 8.11.1).
 * Phase 1 ✓ — Project scaffold, dependencies, theme, FCM init.
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

    override fun onCreate() {
        super.onCreate()

        // -----------------------------------------------------------------
        // Phase 6 · Step 6.4 — CrashHandler MUST be installed as the FIRST
        // non-trivial step.  Any subsequent failure (BuildConfig read,
        // DataStore write, R8-stripped class lookup, etc.) lands in
        // CrashActivity instead of killing the process with no recovery
        // surface — this converts the user-visible "auto-closing" symptom
        // into a real error screen they can React to.  Wrapped in
        // try/catch because we cannot tolerate CrashHandler itself
        // throwing — that would leave the app with no recovery path.
        //
        // Ordering note.  The chained order is now: our CrashHandler →
        // Android KillApplicationHandler.  CrashHandler writes the local
        // dump + launches CrashActivity, then forwards to the OS default
        // which terminates the process.
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
        // initialisation so we don't duplicate telemetry or
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
        // Phase 7 · Step 7.2 — initialise string-encryption subsystem.
        // Registers a ComponentCallbacks2 that wipes the decrypted-value
        // cache when the app enters the background (onTrimMemory).
        runStep("SecurityModule.init") { SecurityModule.init(this) }

        // Phase 7 · Step 7.8 — Boot the Keystore-backed refresh-token
        // store BEFORE any token read (e.g. NetworkInterceptor reads
        // during OkHttp bootstrap). initFromContext is idempotent.
        runStep("TokenStore.init") { TokenStore.initFromContext(applicationContext) }

        // Phase 7 · Step 7.7 — Boot request signer (lazy reads of
        // SIGNING_SECRET from RuntimeStringProvider at first use).
        runStep("RequestSigner.bootstrap") { RequestSigner.bootstrap(applicationContext) }

        // Phase 7 · Step 7.7 — Apply real SSL pins from BuildConfig.
        // SSL_PINS_LEARNGERMANWITH_FUN is a semicolon-separated list
        // injected from gradle into BuildConfig; absent => placeholder.
        runStep("SSLPinner.initialize") { SSLPinner.initialize(coldLaunch = true) }

        // Phase 7 · Step 7.5 + 7.13 — seed IntegrityChecker with the expected
        // signing-cert SHA-256. Per-entry hashing is LAZY inside
        // IntegrityChecker.verifyFileIntegrity — runs on the SecurityGate
        // worker thread, NOT on the main thread — so cold-launch splash is
        // not blocked by zip iteration (review issue #1).
        runStep("IntegrityChecker.bootstrap") {
            val expectedSha = BuildConfig.APP_SIGNING_SHA256.takeIf { it.isNotBlank() }
            if (expectedSha != null) {
                IntegrityChecker.expectedSignatureSha256 = expectedSha
                Log.i(TAG, "IntegrityChecker: signing-cert SHA-256 wired (${expectedSha.take(12)}…)")
            } else {
                Log.d(TAG, "IntegrityChecker: signing-cert SHA-256 blank; signature check will SKIP")
            }
            // v1.1.1 — per-entry hash seeding moved into IntegrityChecker.verifyFileIntegrity
            // as a LAZY fallback (runs only on SecurityGate worker thread, NEVER here).
            // Cold-launch splash is NOT blocked by zip iteration (review issue #1).
        }

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

        // Setup-completeness audit (v1.1.0 maintenance) — detect
        // placeholder Firebase / API configs at boot and log
        // ONE clear breadcrumb per gap. Non-fatal: the app continues
        // to launch. Operators reading Logcat see exactly
        // which credential blocks production. See SecretsValidator.kt.
        runStep("SecretsValidator.validate") {
            SecretsValidator.validate(applicationContext)
        }
    }

    /**
     * Phase 6 · Step 6.4 — Run a single launch step with defensive
     * guarding. Failures are LOGGED so CrashHandler can pick them up,
     * but the launch proceeds normally so the user still reaches
     * SplashActivity and sees the existing error card UI (with Retry)
     * instead of the process silently dying.
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
    }
}
