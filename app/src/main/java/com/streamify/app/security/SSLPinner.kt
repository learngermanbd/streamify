package com.streamify.app.security

import android.util.Log
import okhttp3.CertificatePinner

/**
 * Phase 7 · Step 7.7 — OkHttp SSL certificate pinning.
 *
 * Configures [CertificatePinner] with SHA-256 pins for the API
 * domain.  Provides both the OkHttp-level pinning (programmatic)
 * and documentation for the OS-level pinning (via
 * `network_security_config.xml`).
 *
 * ## Pin rotation
 * When rotating certificates, add the new pin alongside the old
 * one BEFORE deploying the new certificate.  After the old cert
 * expires, remove its pin.  The backup pin (ISRG Root X1 for
 * Let's Encrypt) survives intermediate CA rotation.
 *
 * ## Debug builds
 * In debug builds, pinning is relaxed to allow proxy tools
 * (Charles, Burp Suite) for development.  Release builds enforce
 * strict pinning.
 */
object SSLPinner {

    private const val TAG = "SSLPinner"

    /**
     * SHA-256 certificate pins for the API domain.
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
     * TODO(Phase 7): replace placeholder pins with real production cert pins
     * from the production certificate chain.
     */
    private val PINS = mapOf(
        "learngermanwith.fun" to listOf(
            // Primary: Let's Encrypt Authority X3
            "sha256/YLh1dUR9y6Kja30RrAn7JKnbQG/uEtLMkBgFF2Fuihg=",
            // Backup: ISRG Root X1 (Let's Encrypt root CA)
            "sha256/Vjs8r4z+80wjNcr1YKepWQboSIRi63WsWXhIMN+eWys=",
        )
    )

    /**
     * Build the [CertificatePinner] for use in [okhttp3.OkHttpClient].
     *
     * In debug builds, returns a no-op pinner (empty) so proxy tools
     * work.  In release builds, returns the full pin set.
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
