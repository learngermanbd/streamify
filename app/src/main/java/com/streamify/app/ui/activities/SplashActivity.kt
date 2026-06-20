package com.streamify.app.ui.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.update.UpdateDecision
import com.streamify.app.databinding.ActivitySplashBinding
import com.streamify.app.services.NotificationHelper
import com.streamify.app.ui.update.UpdateActivity
import com.streamify.app.ui.viewmodels.MainViewModel
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import kotlinx.coroutines.launch

/**
 * Phase 3 · Step 3.1 — Splash screen.
 *
 * Phase 5 · Step 5.4 — On API 33+, the splash gate also requests
 * `POST_NOTIFICATIONS` permission so FCM-driven Live Events
 * (`NotificationHelper.showLiveEventNotification`) actually render.
 *
 * Phase 6 · Step 6.2 — The splash gate now ALSO blocks on
 * [com.streamify.app.data.update.AppUpdateManager.checkForUpdate] so
 * a forced-update server response can route to [UpdateActivity] BEFORE
 * the MainActivity forward-navigation. Thinker-gemini step-6.2 review
 * point E: we replaced the original `Handler.postDelayed` pattern with
 * `coroutineScope.launch { delay(2_000); deferred.await() }` so the
 * update check resolves deterministically without a race against the
 * 2-second auto-nav timer.
 *
 *   - UpToDate / Optional / Failed: navigate to MainActivity after at
 *     least `navDelayMs` of loader visibility.
 *   - Forced: cancel the navigateRunnable path; start [UpdateActivity]
 *     (which excludes itself from recents + blocks back), then finish
 *     ourselves so Splash doesn't show underneath.
 *
 * On API < 33 the notification permission is implied at install; no
 * runtime request needed.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    private val mainVm: MainViewModel by lazy {
        val app = application as StreamifyApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(app.repository.mainRepository) as T
            }
        }
        ViewModelProvider(this, factory)[MainViewModel::class.java]
    }

    private val navDelayMs = 2_000L

    /**
     * Phase 5 · Step 5.4 — POST_NOTIFICATIONS runner. We use the
     * modern Activity Result API to avoid `onRequestPermissionsResult`
     * boilerplate.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored — UI only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Phase 6 · Step 6.3 — code-reviewer fix #1: streamify_loading.json
        // bakes a hardcoded cyan (#1CCBD4) into the dot fills, which clashed
        // with the brand @color/primary the CircularProgressIndicator used
        // before the swap. Override at runtime via LottieValueCallback so the
        // animation stays brand-agnostic (reusable in other surface areas
        // without per-screen colour edits) while the splash dots match the
        // @color/primary used everywhere else. Must run before
        // autoPlay's first frame fires (autoPlay triggers on onAttachedToWindow
        // after onCreate returns, so placing this in onCreate is on-time).
        binding.loader.addValueCallback(
            KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            LottieValueCallback(
                PorterDuffColorFilter(
                    ContextCompat.getColor(this, R.color.primary),
                    PorterDuff.Mode.SRC_ATOP
                )
            )
        )

        NotificationHelper.ensureChannels(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "1.0.0"
        }
        binding.versionText.text = getString(R.string.splash_version_template, version)

        binding.retryButton.setOnClickListener {
            binding.errorCard.visibility = android.view.View.GONE
            binding.loader.visibility = android.view.View.VISIBLE
            binding.statusText.text = getString(R.string.splash_loading)
            launchUpdateGate()
        }

        binding.wifiButton.setOnClickListener { openSettingsOrFallback(Settings.ACTION_WIFI_SETTINGS) }
        binding.mobileButton.setOnClickListener { openSettingsOrFallback(Settings.ACTION_DATA_USAGE_SETTINGS) }

        mainVm.load()

        launchUpdateGate()
    }

    /**
     * Phase 6 · Step 6.2 — coroutine splash gate. Keeps the loader
     * visible for at least [navDelayMs] (so users see branding flash)
     * and ALSO waits for the update check to complete before navigating.
     *
     * Two launch sites (`onCreate` + the Retry button) is the reason this
     * is factorized out as a separate method.
     *
     * The Retrofit `dispatcher`-style race from the prior Handler-based
     * implementation is gone — coroutines propagate
     * [kotlinx.coroutines.CancellationException] through structured
     * concurrency so an Activity destroy mid-flight cancels cleanly.
     */
    private fun launchUpdateGate() {
        val app = application as StreamifyApp
        lifecycleScope.launch {
            // Min loader visible — ensures branding flash even on a fast
            // local /api/config. CancellationException propagates to
            // lifecycleScope which cancels on Activity destroy.
            kotlinx.coroutines.delay(navDelayMs)

            val decision = try {
                app.updateManager.checkForUpdate()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                UpdateDecision.UpToDate
            }

            when (decision) {
                is UpdateDecision.Forced -> {
                    UpdateActivity.startForced(
                        context = this@SplashActivity,
                        minVersion = decision.minVersion,
                        apkUrl = decision.apkUrl
                    )
                    finish()
                }
                else -> {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun openSettingsOrFallback(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
