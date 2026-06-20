package com.streamify.app.security

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Phase 7 · Step 7.11 — Secure WebView configuration.
 *
 * Hardens any [WebView] used in the app by:
 *
 *  1. **Disabling unnecessary features** — JavaScript, file access,
 *     content access, universal file access, mixed content.
 *  2. **URL validation** — whitelist of allowed domains; blocks
 *     `intent://`, `file://`, `data://`, `javascript:` schemes.
 *  3. **Redirect protection** — overrides `shouldOverrideUrlLoading`
 *     to validate every navigation.
 *  4. **Sensitive data clearing** — clears cookies, cache, and
 *     WebView storage on demand.
 *
 * ## Usage
 * ```kotlin
 * SecureWebView.configure(webView, context)
 * webView.loadUrl("https://learngermanwith.fun/info")
 * // When done:
 * SecureWebView.clearSensitiveData(webView)
 * ```
 */
object SecureWebView {

    private const val TAG = "SecureWebView"

    /**
     * Domains that the WebView is allowed to load.
     * All other domains are blocked.
     */
    private val ALLOWED_DOMAINS = setOf(
        "learngermanwith.fun",
        "www.learngermanwith.fun",
        "api.learngermanwith.fun",
    )

    /**
     * Configure a [WebView] with security-hardened settings.
     *
     * @param webView The WebView to configure.
     * @param context Application or Activity context.
     * @param allowedDomains Optional override for the domain whitelist.
     */
    fun configure(
        webView: WebView,
        context: Context,
        allowedDomains: Set<String> = ALLOWED_DOMAINS
    ) {
        webView.settings.apply {
            // ── Disable unnecessary features ──────────────────────
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }

            // ── Block mixed content (HTTP on HTTPS page) ─────────
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            // ── Prevent saving of form data ──────────────────────
            saveFormData = false
            savePassword = false

            // ── Disable zoom for consistent rendering ────────────
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // ── Cache mode — no caching of sensitive pages ───────
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        // ── Set secure WebViewClient ─────────────────────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return true
                return if (isUrlAllowed(url, allowedDomains)) {
                    false // Allow the navigation
                } else {
                    Log.w(TAG, "Blocked navigation to: $url")
                    true // Block the navigation
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "Page loading: $url")
            }
        }

        // ── Set minimal WebChromeClient ──────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            // No file chooser, no permission requests, no console messages
        }

        // ── Disable WebView debugging in release ─────────────────
        if (!com.streamify.app.BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        Log.d(TAG, "WebView secured for domains: $allowedDomains")
    }

    /**
     * Check if a URL is allowed by the security policy.
     *
     * @return `true` if the URL passes all checks.
     */
    fun isUrlAllowed(url: Uri, allowedDomains: Set<String> = ALLOWED_DOMAINS): Boolean {
        // ── Check 1: Scheme must be HTTPS (or HTTP in debug) ─────
        val scheme = url.scheme?.lowercase()
        when (scheme) {
            "https" -> { /* allowed */ }
            "http" -> {
                if (!com.streamify.app.BuildConfig.DEBUG) {
                    Log.w(TAG, "Blocked HTTP in release: $url")
                    return false
                }
            }
            else -> {
                Log.w(TAG, "Blocked scheme: $scheme")
                return false
            }
        }

        // ── Check 2: Domain must be in the whitelist ─────────────
        val host = url.host?.lowercase() ?: return false
        if (host !in allowedDomains) {
            Log.w(TAG, "Blocked domain: $host")
            return false
        }

        return true
    }

    /**
     * Clear all sensitive data from a WebView.
     * Call when the WebView is no longer needed or when the user
     * navigates away from sensitive content.
     */
    fun clearSensitiveData(webView: WebView) {
        webView.apply {
            clearHistory()
            clearCache(true)
            clearFormData()
        }

        // Clear cookies
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        Log.d(TAG, "Sensitive WebView data cleared")
    }

    /**
     * Load a URL into a WebView with security validation.
     *
     * @return `true` if the URL was loaded, `false` if blocked.
     */
    fun loadUrl(webView: WebView, url: String, allowedDomains: Set<String> = ALLOWED_DOMAINS): Boolean {
        val uri = Uri.parse(url)
        return if (isUrlAllowed(uri, allowedDomains)) {
            webView.loadUrl(url)
            true
        } else {
            Log.w(TAG, "Refusing to load blocked URL: $url")
            false
        }
    }
}
