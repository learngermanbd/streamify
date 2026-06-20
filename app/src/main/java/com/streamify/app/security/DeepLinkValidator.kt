package com.streamify.app.security

import android.net.Uri
import android.util.Log
import java.net.URLDecoder

/**
 * Phase 7 · Step 7.11 — Deep link validation and sanitization.
 *
 * Validates all incoming deep links before they're processed by the
 * app.  Prevents:
 *
 *  1. **Malformed URIs** — blocks URIs that don't match the expected
 *     format or contain invalid characters.
 *  2. **Open redirects** — blocks deep links that would redirect
 *     the user to an external site.
 *  3. **Scheme injection** — blocks `javascript:`, `intent:`,
 *     `file:`, `data:` and other dangerous schemes.
 *  4. **Path traversal** — blocks `..` and encoded traversal
 *     attempts in the path.
 *  5. **Parameter injection** — sanitizes query parameters to
 *     prevent XSS or SQL injection via deep link params.
 *
 * ## Expected deep link formats
 * ```
 * streamify://main
 * streamify://player/{channelId}
 * streamify://category/{categoryId}
 * streamify://notice/{noticeId}
 * ```
 *
 * ## Usage
 * ```kotlin
 * val result = DeepLinkValidator.validate(deepLinkUri)
 * if (result is DeepLinkResult.Valid) {
 *     // Process the deep link
 * } else {
 *     // Reject — result contains the reason
 * }
 * ```
 */
object DeepLinkValidator {

    private const val TAG = "DeepLinkValidator"

    /** The app's deep link scheme. */
    private const val APP_SCHEME = "streamify"

    /** Allowed host values (null host means scheme-only URI like `streamify://main`). */
    private val ALLOWED_HOSTS = setOf(
        "main",
        "player",
        "category",
        "notice",
        "settings",
        "update",
        "search",
    )

    /** Maximum URI length to prevent buffer overflow attacks. */
    private const val MAX_URI_LENGTH = 2048

    /** Characters that are never allowed in any deep link component. */
    private val DANGEROUS_CHARS = charArrayOf('<', '>', '"', '\'', '{', '}', '|', '\\', '^', '`')

    /** Patterns that indicate path traversal attempts. */
    private val TRAVERSAL_PATTERNS = listOf(
        "..", "%2e%2e", "%2E%2E", "..%2f", "%2e%2e%2f",
        "..\\", "%2e%2e%5c", "..%5c",
    )

    /**
     * Validate an incoming deep link URI.
     *
     * @param uri The deep link URI to validate.
     * @param caller Optional identifier for logging (e.g., activity name).
     * @return [DeepLinkResult.Valid] if the URI passes all checks,
     *   or [DeepLinkResult.Invalid] with the rejection reason.
     */
    fun validate(uri: Uri?, caller: String = "unknown"): DeepLinkResult {
        if (uri == null) {
            Log.w(TAG, "Null deep link from $caller")
            return DeepLinkResult.Invalid("null_uri", caller)
        }

        val uriString = uri.toString()

        // ── Check 1: Length limit ────────────────────────────────
        if (uriString.length > MAX_URI_LENGTH) {
            Log.w(TAG, "URI too long (${uriString.length}) from $caller")
            return DeepLinkResult.Invalid("uri_too_long", caller)
        }

        // ── Check 2: Scheme validation ───────────────────────────
        val scheme = uri.scheme?.lowercase()
        if (scheme == null) {
            Log.w(TAG, "Missing scheme from $caller: $uriString")
            return DeepLinkResult.Invalid("missing_scheme", caller)
        }

        if (scheme != APP_SCHEME && scheme != "https") {
            Log.w(TAG, "Blocked scheme '$scheme' from $caller")
            return DeepLinkResult.Invalid("blocked_scheme:$scheme", caller)
        }

        // ── Check 3: HTTPS deep links must be from allowed domains ──
        if (scheme == "https") {
            val host = uri.host?.lowercase() ?: ""
            if (!host.endsWith("learngermanwith.fun")) {
                Log.w(TAG, "Blocked HTTPS host '$host' from $caller")
                return DeepLinkResult.Invalid("blocked_host:$host", caller)
            }
        }

        // ── Check 4: App scheme host/path validation ─────────────
        if (scheme == APP_SCHEME) {
            // In `streamify://main`, "main" is the host
            val host = uri.host?.lowercase()
            if (host == null || host !in ALLOWED_HOSTS) {
                Log.w(TAG, "Blocked host '$host' from $caller")
                return DeepLinkResult.Invalid("blocked_app_host:$host", caller)
            }
        }

        // ── Check 5: Dangerous characters ────────────────────────
        for (component in listOf(uri.host, uri.path, uri.query, uri.fragment)) {
            if (component != null) {
                for (ch in DANGEROUS_CHARS) {
                    if (ch in component) {
                        Log.w(TAG, "Dangerous character '$ch' in URI from $caller")
                        return DeepLinkResult.Invalid("dangerous_char:$ch", caller)
                    }
                }
            }
        }

        // ── Check 6: Path traversal ──────────────────────────────
        val path = uri.path?.lowercase() ?: ""
        for (pattern in TRAVERSAL_PATTERNS) {
            if (pattern in path) {
                Log.e(TAG, "Path traversal detected from $caller: $pattern")
                return DeepLinkResult.Invalid("path_traversal:$pattern", caller)
            }
        }

        // ── Check 7: URL-encoded re-validation ──────────────────
        // Decode and re-check for double-encoding attacks
        try {
            val decoded = URLDecoder.decode(uriString, "UTF-8")
            if (decoded != uriString) {
                // Re-validate the decoded version
                val decodedUri = Uri.parse(decoded)
                val decodedScheme = decodedUri.scheme?.lowercase()
                if (decodedScheme != null && decodedScheme !in setOf(APP_SCHEME, "https", "http")) {
                    Log.w(TAG, "Decoded scheme '$decodedScheme' is blocked from $caller")
                    return DeepLinkResult.Invalid("encoded_blocked_scheme", caller)
                }

                // Check decoded path for traversal
                val decodedPath = decodedUri.path?.lowercase() ?: ""
                for (pattern in TRAVERSAL_PATTERNS) {
                    if (pattern in decodedPath) {
                        Log.e(TAG, "Encoded path traversal from $caller")
                        return DeepLinkResult.Invalid("encoded_path_traversal", caller)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "URL decode failed from $caller: ${e.message}")
            return DeepLinkResult.Invalid("decode_error", caller)
        }

        // ── Check 8: Open redirect prevention ────────────────────
        // If the URI contains a redirect parameter, validate its target
        val redirectParams = listOf("url", "redirect", "next", "return", "continue", "target")
        for (param in redirectParams) {
            val redirectTarget = uri.getQueryParameter(param)
            if (redirectTarget != null) {
                val redirectUri = Uri.parse(redirectTarget)
                val redirectScheme = redirectUri.scheme?.lowercase()
                if (redirectScheme != null && redirectScheme !in setOf(APP_SCHEME, "https")) {
                    Log.w(TAG, "Open redirect blocked from $caller: $redirectTarget")
                    return DeepLinkResult.Invalid("open_redirect:$param", caller)
                }
            }
        }

        Log.d(TAG, "Deep link validated: $uriString (from $caller)")
        return DeepLinkResult.Valid(uri)
    }

    /**
     * Sanitize a deep link URI by stripping dangerous components.
     * Returns a cleaned URI or null if the URI is fundamentally invalid.
     */
    fun sanitize(uri: Uri): Uri? {
        val builder = Uri.Builder()
            .scheme(uri.scheme)
            .authority(uri.host)

        // Copy path, removing traversal patterns
        val safePath = uri.path?.replace("..", "")?.replace("%2e", "")
        if (safePath != null) {
            builder.path(safePath)
        }

        // Copy only safe query parameters
        uri.queryParameterNames.forEach { name ->
            val safeName = name.filter { it !in DANGEROUS_CHARS }
            val safeValue = uri.getQueryParameter(name)?.filter { it !in DANGEROUS_CHARS }
            if (safeName.isNotBlank() && safeValue != null) {
                builder.appendQueryParameter(safeName, safeValue)
            }
        }

        return try {
            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sanitize URI: ${e.message}")
            null
        }
    }
}

/**
 * Result of deep link validation.
 */
sealed class DeepLinkResult {
    /** Deep link is valid and safe to process. */
    data class Valid(val uri: Uri) : DeepLinkResult()
    /** Deep link is invalid and should be rejected. */
    data class Invalid(val reason: String, val caller: String) : DeepLinkResult()
}
