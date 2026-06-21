package com.streamify.app.security

import android.util.Log
import okhttp3.CertificatePinner

/**
 * Phase 7 · Step 7.7 — OkHttp SSL certificate pinning.
 *
 * Configures [CertificatePinner] with SHA-256 pins for the API
 * domain. Provides both the OkHttp-level pinning (programmatic)
 * and documentation for the OS-level pinning (via
 * `network_security_config.xml`).
 *
 * ## Pin rotation
 * When rotating certificates, add the new pin alongside the old
 * one BEFORE deploying the new certificate. After the old cert
 * expires, remove its pin. The backup pin (ISRG Root X1 for
 * Let's Encrypt) survives intermediate CA rotation.
 *
 * ## v1.1.1 hardening — runtime config + real pin injection
 *
 * - The [PINS] map is `[domain] → [pin]` pairs and is mutable
 *   so [configure] can OVERWRITE the production domain's pin
 *   set at cold-launch from `BuildConfig.SSL_PIN_<DOMAIN>`.
 * - [initialize] is called from
 *   [com.streamify.app.StreamifyApp.onCreate] and applies the
 *   BuildConfig override IF present, falling back to the
 *   baked-in placeholder set if absent.
 *
 * ## Debug builds
 * In debug builds, pinning is relaxed to allow proxy tools
 * (Charles, Burp Suite) for development. Release builds enforce
 * strict pinning.
 */
object SSLPinner {

    private const val TAG = "SSLPinner"

    /**
     * SHA-256 certificate pins keyed by domain.
     *
     * Format: `sha256/<base64-encoded-SPKI-hash>`
     *
     * To generate a pin:
     * ```
     * openssl s_client -connect DOMAIN:443 -servername DOMAIN < /dev/null 2>/dev/null \
     *   | openssl x509 -pubkey -noout \
     *   | openssl pkey -pubin -outform DER \
     *   | openssl dgst -sha256 -binary \
     *   | base64
     * ```
     *
     * v1.1.1: this map is now mutable so production
     * [BuildConfig.SSL_PINS_LEARNGERMANWITH_FUN] (or any
     * `SSL_PINS_<UPPER_DOMAIN>` field) can override the
     * placeholder pins per build.
     */
    // Pins regenerated 2026-06-21 from the live cert chain via openssl
    // s_client -connect learngermanwith.fun:443. Order: leaf first,
    // backup is the GTS WE1 intermediate so a leaf-cert renewal is
    // survivable without an app update.
    private val PINS: MutableMap<String, MutableList<String>> = mutableMapOf(
        "learngermanwith.fun" to mutableListOf(
            // Primary: leaf (CN=learngermanwith.fun)
            "sha256/IGIlXMkDynwXVTT3GmncxsWOhz5LUyeVTiN/U/ouGeo=",
            // Backup: GTS WE1 intermediate
            "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        )
    )

    /**
     * Phase 7 · Step 7.7 — public entry-point used by
     * [initialize] to swap the production-domain pin set with
     * real production cert pins at runtime. Replaces any current
     * entries for the given [domain] with [pins] (preserves
     * unknown domains).
     */
    @JvmStatic
    fun configure(domain: String, vararg pins: String) {
        if (domain.isBlank() || pins.isEmpty()) {
            Log.w(TAG, "configure() called with blank domain or zero pins; ignoring")
            return
        }
        PINS[domain] = pins.toMutableList()
        Log.i(TAG, "configure: domain=$domain pins=${pins.joinToString()}")
    }

    /**
     * Phase 7 · Step 7.7 — entry-point called from
     * [com.streamify.app.StreamifyApp.onCreate]. Reads any
     * `SSL_PINS_<UPPER_DOMAIN>` constant from BuildConfig and
     * applies via [configure].
     *
     * The format is: semicolon-separated `sha256/...` strings
     * inside the BuildConfig.String constant. Empty constant =>
     * no override (placeholder pins remain).
     */
    @JvmStatic
    fun initialize(@Suppress("UNUSED_PARAMETER") coldLaunch: Boolean = true) {
        configOverrides.forEach { (domain, pinsConstant) ->
            val pinsCsv = pinsConstant.trim()
            if (pinsCsv.isBlank()) return@forEach
            val pinList = pinsCsv.split(';').map { it.trim() }.filter { it.isNotBlank() }
            if (pinList.isEmpty()) return@forEach
            configure(domain, *pinList.toTypedArray())
        }
        Log.d(TAG, "SSL pin set applied: ${PINS.mapValues { (_, v) -> v.size }}")
    }

    /**
     * Phase 7 · Step 7.7 — explicit map of `domain -> BuildConfig
     * constant` so [initialize] can probe the constants without
     * listing every domain in code. New domains can be added to
     * [PINS] overload at zero compile-time cost.
     */
    private val configOverrides: Map<String, String> by lazy {
        // No reflection — the constants are looked up here at
        // build-and-link time. Adding a domain requires touching
        // both this map AND [PINS] AND build.gradle.kts, which
        // is intentional: we want a code-visible diff per domain.
        mapOf(
            "learngermanwith.fun" to readSslPinsFromBuild(),
        )
    }

    /**
     * Probe the BuildConfig for an `SSL_PINS_LEARNGERMANWITH_FUN`
     * constant via reflection. Absent constant => empty string
     * => no override (placeholder pins remain).
     *
     * Reflection-based because each domain would otherwise
     * force a transitive property on [com.streamify.app.BuildConfig].
     */
    private fun readSslPinsFromBuild(): String {
        return runCatching {
            val cls = Class.forName("com.streamify.app.BuildConfig")
            val field = cls.getField("SSL_PINS_LEARNGERMANWITH_FUN")
            field.get(null) as? String ?: ""
        }.getOrDefault("")
    }

    /**
     * Build the [CertificatePinner] for use in [okhttp3.OkHttpClient].
     *
     * In debug builds, returns a no-op pinner (empty) so proxy tools
     * work. In release builds, returns the full pin set.
     */
    fun buildCertificatePinner(isDebug: Boolean): CertificatePinner {
        if (isDebug) {
            Log.d(TAG, "Debug build — SSL pinning relaxed for proxy tools")
            return CertificatePinner.DEFAULT // No pins
        }

        val builder = CertificatePinner.Builder()
        for ((domain, pins) in PINS) {
            for (pin in pins) {
                builder.add(domain, pin)
            }
            Log.d(TAG, "Added ${pins.size} pins for $domain")
        }
        return builder.build()
    }
}
