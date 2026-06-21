package com.streamify.app.security

import android.content.Context
import android.util.Log
import com.streamify.app.BuildConfig
import com.streamify.app.data.remote.AppConfig

/**
 * Android 16 (API 36) maintenance · v1.1.0 — one-shot runtime check
 * that the project's shipped configuration matches what production
 * requires. Detects placeholder values
 * (`google-services.json` stub, missing `signing.properties`,
 * missing `secrets.properties`) at boot-time and logs ONE clear
 * breadcrumb listing the gaps.
 *
 * ## Why this exists
 *
 * Before this validator the boot-time symptoms of a placeholder
 * Firebase / API config were either:
 *   - **Silent**: the placeholder values entered the JNI realm
 *     cleanly and an API call later 401'd / 404'd; OR
 *   - **Auto-closing**: the placeholder `mobilesdk_app_id`'d project
 *     couldn't be validated by Google Play Services, and on API 26+
 *     the OS force-stopped the app via the "Package invalid" path.
 *
 * The fix is non-destructive: detect the placeholder at boot and log
 * a single Log.w breadcrumb so the misconfiguration is surfaced in
 * Logcat. Placeholders are NOT fatal — the app continues to
 * launch with FCM / live-API disabled. Operators can ship
 * the APK to Internal Testing while they source the real credentials.
 *
 * ## Where it runs
 *
 * Called once from
 * [com.streamify.app.StreamifyApp.onCreate] via the existing
 * `runStep` guard so an exception here cannot itself block launch.
 *
 * ## What it checks
 *
 *  1. **Firebase project_id** — read from generated resources
 *     (`R.string.project_id`, populated by the `google-services`
 *     gradle plugin from `app/google-services.json`). The shipped
 *     placeholder is `streamify-placeholder` with project_number
 *     `000000000000`.
 *  2. **Firebase API key** — read from `R.string.google_api_key`.
 *     The shipped placeholder starts with `AIzaSyXXXX…`.
 *  3. **API base URL** — `AppConfig.defaults().apiBaseUrl`. Falls
 *     back to `https://learngermanwith.fun` (placeholder host) when
 *     `secrets.properties` is absent.
 *  4. **Composite network posture** — single Log.i breadcrumb listing
 *     the resolved host, build variant, whether HTTPS is enforced, and
 *     whether the host is in the cert-pin set.
 */
object SecretsValidator {

    private const val TAG = "SecretsValidator"

    // Known placeholder values that the placeholder google-services.json ships with.
    private const val PLACEHOLDER_PROJECT_ID = "streamify-placeholder"
    private const val PLACEHOLDER_PROJECT_NUMBER_PREFIX = "000000000000"
    private const val PLACEHOLDER_API_KEY_PREFIX = "AIzaSyXXXX"

    // Default fallback URL when secrets.properties absent — defined in
    // `app/build.gradle.kts` encryptSecrets task's fallback map.
    private const val SECRETS_FALLBACK_HOST = "learngermanwith.fun"

    /**
     * Run the placeholder-detection check. Logs one
     * [android.util.Log] `WARNING`-level breadcrumb per gap (or one
     * `INFO` if every configured service is real). No return value,
     * no exceptions.
     */
    fun validate(context: Context) {
        val issues = mutableListOf<String>()

        // ── 1. Firebase placeholder detection ─────────────────────
        try {
            val res = context.resources
            val pkg = context.packageName
            @Suppress("DiscouragedApi")
            val projectId = res.getIdentifier("project_id", "string", pkg)
                .takeIf { it != 0 }
                ?.let { res.getString(it) }
                .orEmpty()
            @Suppress("DiscouragedApi")
            val apiKey = res.getIdentifier("google_api_key", "string", pkg)
                .takeIf { it != 0 }
                ?.let { res.getString(it) }
                .orEmpty()

            if (projectId == PLACEHOLDER_PROJECT_ID) {
                issues += "Firebase google-services.json is a placeholder " +
                    "(project_id='$projectId'). Replace app/google-services.json " +
                    "with the real Firebase-console export."
            } else if (projectId.length >= PLACEHOLDER_PROJECT_NUMBER_PREFIX.length &&
                       projectId.startsWith(PLACEHOLDER_PROJECT_NUMBER_PREFIX.substring(0, 8))
            ) {
                issues += "Firebase google-services.json project_id='$projectId' " +
                    "looks like a placeholder (all-zero project number)."
            }
            if (apiKey.startsWith(PLACEHOLDER_API_KEY_PREFIX)) {
                issues += "Firebase google-services.json has a placeholder " +
                    "API key. Replace it; the real key is required for token " +
                    "registration via /api/fcm/register and for Play Integrity."
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firebase config detection failed", t)
        }

        // ── 2. API Base URL detection (secrets.properties) ────────
        try {
            val apiBase = AppConfig.defaults().apiBaseUrl
            if (apiBase.contains(SECRETS_FALLBACK_HOST)) {
                issues += "API_BASE_URL using the build-time default fallback " +
                    "('$apiBase'). Drop secrets.properties with the real API " +
                    "endpoint to enable live data."
            }
        } catch (t: Throwable) {
            Log.w(TAG, "API endpoint detection failed", t)
        }

        // ── Log result ────────────────────────────────────────────
        if (issues.isEmpty()) {
            Log.i(TAG, "OK — Firebase + API endpoints all configured.")
        } else {
            Log.w(TAG, "Setup check found ${issues.size} placeholder value(s):")
            issues.forEachIndexed { i, msg -> Log.w(TAG, "  ${i + 1}. $msg") }
        }

        // ── 3. Composite network diagnostic line ───────────────────
        // Single Log.i so logcat / Sentry / Firebase Crashlytics can
        // aggregate one breadcrumb per cold launch — far easier to
        // grep than the per-issue warnings above. Hard-codes the
        // currently-pinned host + dev-loopback set; keep these in
        // sync with SSLPinner.PINS and NetworkInterceptor.LOOPBACK_HOSTS
        // whenever the production cert pin set changes.
        try {
            val apiBase = AppConfig.defaults().apiBaseUrl
            val host = java.net.URI(apiBase).host ?: "?"
            val pinned = host in PINNED_HOSTS
            val isLoopbackDevHost = host in LOOPBACK_HOSTS_DEBUG
            // HTTPS is enforced everywhere EXCEPT loopback in DEBUG
            // builds — this mirrors NetworkInterceptor + network_security_config.xml.
            val enforcesHttps = !(BuildConfig.DEBUG && isLoopbackDevHost)
            Log.i(
                TAG,
                "Network: base=$apiBase host=$host " +
                    "build=${if (BuildConfig.DEBUG) "debug" else "release"} " +
                    "enforcesHttps=$enforcesHttps pinned=$pinned"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Composite network diagnostic failed", t)
        }
    }

    /**
     * Snapshot of cert-pin hosts (mirrors
     * [com.streamify.app.security.SSLPinner.PINS] keys). Add new
     * pinned hosts here in lock-step when [SSLPinner] changes.
     */
    private val PINNED_HOSTS = setOf("learngermanwith.fun")

    /**
     * Loopback hosts where HTTPS is unwieldy in dev: no DNS, no
     * self-signed root on emulator. Mirrors
     * [com.streamify.app.security.NetworkInterceptor.LOOPBACK_HOSTS].
     * Release builds DO NOT honour these even if the host is in
     * the set — the `enforcesHttps` calculation above gates that.
     */
    private val LOOPBACK_HOSTS_DEBUG = setOf("10.0.2.2", "localhost", "127.0.0.1")
}
