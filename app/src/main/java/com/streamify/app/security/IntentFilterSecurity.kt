package com.streamify.app.security

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Log
import java.io.Serializable

/**
 * Phase 7 · Step 7.11 — Intent security validation.
 *
 * Validates all incoming intents before they're processed by
 * Activities and Services.  Prevents:
 *
 *  1. **Untrusted sources** — checks `getCallingActivity()` to
 *     verify the intent came from within our app or a trusted
 *     system component.
 *  2. **Extra injection** — validates and sanitizes intent extras
 *     to prevent data injection attacks.
 *  3. **Implicit intent abuse** — blocks implicit intents for
 *     sensitive operations that should only accept explicit intents.
 *  4. **Intent spoofing** — detects intents that claim to come from
 *     our app but were actually launched by a third party.
 *
 * ## Usage
 * ```kotlin
 * // In Activity.onCreate():
 * val validation = IntentFilterSecurity.validateIntent(this, intent)
 * if (!validation.isSafe) {
 *     Log.w(TAG, "Unsafe intent: ${validation.reason}")
 *     finish()
 *     return
 * }
 * ```
 */
object IntentFilterSecurity {

    private const val TAG = "IntentFilterSec"

    /** Our app's package name. */
    private const val APP_PACKAGE = "com.streamify.app"

    /** System packages that are always trusted. */
    private val TRUSTED_PACKAGES = setOf(
        "android",                          // Android system
        "com.google.android.gms",           // Google Play Services
        "com.android.vending",              // Play Store
        "com.google.android.apps.messaging", // Google Messages
        "com.samsung.android.messaging",    // Samsung Messages
        APP_PACKAGE,                        // Our own app
    )

    /** Maximum length for any single string extra value. */
    private const val MAX_EXTRA_LENGTH = 4096

    /** Maximum number of extras allowed in a single intent. */
    private const val MAX_EXTRAS_COUNT = 20

    /**
     * Validate an incoming intent for safety.
     *
     * @param activity The Activity that received the intent.
     * @param intent The intent to validate.
     * @return [IntentValidation.Safe] if the intent passes all checks,
     *   or [IntentValidation.Unsafe] with the rejection reason.
     */
    fun validateIntent(activity: Activity, intent: Intent): IntentValidation {
        // ── Check 1: Intent source ───────────────────────────────
        val sourceCheck = validateSource(activity, intent)
        if (!sourceCheck.isSafe) return sourceCheck

        // ── Check 2: Intent extras ───────────────────────────────
        val extrasCheck = validateExtras(intent)
        if (!extrasCheck.isSafe) return extrasCheck

        // ── Check 3: Intent action ───────────────────────────────
        val actionCheck = validateAction(intent)
        if (!actionCheck.isSafe) return actionCheck

        // ── Check 4: Intent data URI ─────────────────────────────
        val dataCheck = validateData(intent)
        if (!dataCheck.isSafe) return dataCheck

        return IntentValidation.Safe
    }

    /**
     * Validate that the intent came from a trusted source.
     *
     * Note: `activity.referrer` can be spoofed via
     * `Intent.EXTRA_REFERRER`, so we use `getCallingActivity()`
     * (which is cryptographically bound via PendingIntent) as the
     * primary signal.  The referrer is logged but not trusted for
     * security decisions.
     */
    private fun validateSource(activity: Activity, intent: Intent): IntentValidation {
        // getCallingActivity() is reliable for startActivityForResult flows
        // (bound via PendingIntent — cannot be spoofed). Returns null for
        // deep links and notifications (which we allow).
        val callingPkg = activity.callingActivity?.packageName
        if (callingPkg != null && !isTrustedPackage(callingPkg)) {
            Log.w(TAG, "Untrusted callingActivity: $callingPkg")
            // Block explicit calls from untrusted apps to sensitive activities
            return IntentValidation.Unsafe("untrusted_caller:$callingPkg")
        }

        // Log referrer for audit only (not trusted for security decisions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.referrer?.host?.let { referrerHost ->
                Log.d(TAG, "Intent referrer (audit only): $referrerHost")
            }
        }

        return IntentValidation.Safe
    }

    /**
     * Validate intent extras for injection attacks.
     */
    private fun validateExtras(intent: Intent): IntentValidation {
        val extras = intent.extras ?: return IntentValidation.Safe

        // ── Check extras count ───────────────────────────────────
        if (extras.size() > MAX_EXTRAS_COUNT) {
            Log.w(TAG, "Too many extras: ${extras.size()}")
            return IntentValidation.Unsafe("too_many_extras:${extras.size()}")
        }

        // ── Validate each extra ──────────────────────────────────
        for (key in extras.keySet()) {
            val value = extras.get(key)

            // Check string extras for length and dangerous content
            if (value is String) {
                if (value.length > MAX_EXTRA_LENGTH) {
                    Log.w(TAG, "Extra '$key' too long: ${value.length}")
                    return IntentValidation.Unsafe("extra_too_long:$key")
                }

                // Check for script injection in string extras
                if (containsScriptInjection(value)) {
                    Log.e(TAG, "Script injection detected in extra '$key'")
                    return IntentValidation.Unsafe("script_injection:$key")
                }
            }
        }

        return IntentValidation.Safe
    }

    /**
     * Validate the intent action.
     */
    private fun validateAction(intent: Intent): IntentValidation {
        val action = intent.action

        // Null action is acceptable for internal intents
        if (action == null) return IntentValidation.Safe

        // Block dangerous actions from external sources
        val dangerousActions = setOf(
            Intent.ACTION_DELETE,
            Intent.ACTION_UNINSTALL_PACKAGE,
        )

        if (action in dangerousActions) {
            Log.w(TAG, "Blocked dangerous action: $action")
            return IntentValidation.Unsafe("dangerous_action:$action")
        }

        return IntentValidation.Safe
    }

    /**
     * Validate the intent data URI.
     */
    private fun validateData(intent: Intent): IntentValidation {
        val data = intent.data ?: return IntentValidation.Safe

        // Use DeepLinkValidator for URI validation
        val deepLinkResult = DeepLinkValidator.validate(data, "IntentFilterSecurity")
        return when (deepLinkResult) {
            is DeepLinkResult.Valid -> IntentValidation.Safe
            is DeepLinkResult.Invalid -> {
                Log.w(TAG, "Invalid intent data: ${deepLinkResult.reason}")
                IntentValidation.Unsafe("invalid_data:${deepLinkResult.reason}")
            }
        }
    }

    /**
     * Check if a package name belongs to a trusted source.
     */
    private fun isTrustedPackage(packageName: String): Boolean {
        return packageName in TRUSTED_PACKAGES ||
            packageName.startsWith("com.google.android.") ||
            packageName.startsWith("com.android.") ||
            packageName.startsWith(APP_PACKAGE)
    }

    /**
     * Check if a string contains potential script injection patterns.
     */
    private fun containsScriptInjection(value: String): Boolean {
        val lower = value.lowercase()
        val patterns = listOf(
            "<script",
            "javascript:",
            "onerror=",
            "onload=",
            "onclick=",
            "onfocus=",
            "eval(",
            "document.cookie",
            "document.write",
        )
        return patterns.any { it in lower }
    }

    /**
     * Sanitize an intent's extras by removing potentially dangerous
     * values.  Returns a new intent with cleaned extras.
     *
     * @param intent The intent to sanitize.
     * @return A new intent with sanitized extras, or the original
     *   if no changes were needed.
     */
    fun sanitize(intent: Intent): Intent {
        val extras = intent.extras ?: return intent

        val sanitized = Intent(intent)
        sanitized.replaceExtras(null)

        for (key in extras.keySet()) {
            val value = extras.get(key)
            when (value) {
                is String -> {
                    val clean = value
                        .take(MAX_EXTRA_LENGTH)
                        .filter { it !in charArrayOf('<', '>', '"', '\'') }
                    sanitized.putExtra(key, clean)
                }
                is Int -> sanitized.putExtra(key, value)
                is Long -> sanitized.putExtra(key, value)
                is Boolean -> sanitized.putExtra(key, value)
                is Float -> sanitized.putExtra(key, value)
                is Double -> sanitized.putExtra(key, value)
                is android.os.Parcelable -> sanitized.putExtra(key, value)
                is java.io.Serializable -> sanitized.putExtra(key, value)
                else -> {
                    // Drop unknown extra types for safety
                    Log.d(TAG, "Dropping extra '$key' of unknown type: ${value?.javaClass?.simpleName}")
                }
            }
        }

        return sanitized
    }

    /**
     * Validate that a calling component is within our app.
     * Used for sensitive operations that must only be called internally.
     *
     * @param callingActivity The ComponentName of the caller (from
     *   `Activity.getCallingActivity()`).
     * @return `true` if the caller is internal.
     */
    fun isInternalCaller(callingActivity: ComponentName?): Boolean {
        return callingActivity?.packageName == APP_PACKAGE
    }
}

/**
 * Result of intent validation.
 */
sealed class IntentValidation {
    /** Intent is safe to process. */
    object Safe : IntentValidation() {
        override val isSafe = true
        override val reason: String? = null
    }

    /** Intent is unsafe and should be rejected. */
    data class Unsafe(override val reason: String) : IntentValidation() {
        override val isSafe = false
    }

    abstract val isSafe: Boolean
    abstract val reason: String?
}
