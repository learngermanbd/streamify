package com.streamify.app.security

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log

/**
 * Phase 7 · Step 7.2 — Dependency-injection seam for the string
 * encryption subsystem.
 *
 * Call [init] once from [com.streamify.app.StreamifyApp.onCreate].
 * It registers a [ComponentCallbacks2] on the [Context] so that
 * [RuntimeStringProvider.clearCache] fires automatically when the OS
 * signals memory pressure or the app enters the background.
 */
object SecurityModule {

    private const val TAG = "SecurityModule"
    private var initialized = false

    /**
     * Initialise the security subsystem and register the
     * lifecycle-aware cache-clear callback.
     *
     * Idempotent: subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // TRIM_MEMORY_UI_HIDDEN = 20 — app is no longer visible.
                // Higher levels (RUNNING_LOW, RUNNING_CRITICAL, etc.)
                // also warrant a wipe.
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    RuntimeStringProvider.clearCache()
                    Log.d(TAG, "onTrimMemory($level) → cache cleared")
                }
            }

            override fun onLowMemory() {
                RuntimeStringProvider.clearCache()
                Log.d(TAG, "onLowMemory → cache cleared")
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                // No-op — encryption state is not locale/theme dependent.
            }
        })

        Log.d(TAG, "SecurityModule initialised; cache-clear callback registered")
    }
}
