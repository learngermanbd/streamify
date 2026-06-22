package com.streamify.app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.GravityCompat
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.update.UpdateDecision
import com.streamify.app.databinding.ActivityMainBinding
import com.streamify.app.ui.common.UiState
import com.streamify.app.ui.update.UpdateActivity
import com.streamify.app.ui.update.UpdateDialogFragment
import com.streamify.app.ui.update.showUpdateDialog
import com.streamify.app.ui.viewmodels.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3 · Step 3.2 — Main host Activity.
 *
 * Hosts the BottomNavigationView (4 tabs) + the Drawer menu (10 items) +
 * (Phase 5 · Step 5.6) the MaterialToolbar with a Search action.
 *
 * Phase 6 · Step 6.2 — drawer_update wires the optional-update flow.
 *  - Forced branch is treated as a user mid-app tap (very rare — the
 *    splash gate should have caught it) and routes to UpdateActivity.
 *  - Optional -> UpdateDialogFragment.newOptional (BottomSheet).
 *  - UpToDate -> single-line Snackbar with "Up to date".
 *  - Failed   -> single-line Snackbar with the failure reason.
 *
 * Window-inset policy: the toolbar absorbs the top system-bar inset
 * (status bar) so its title sits below the camera notch / cutout; the
 * NavHost no longer pads itself for top because the toolbar now sits
 * above it. The bottom-nav still absorbs the bottom system-bar inset.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val mainVm: MainViewModel by lazy {
        val app = application as StreamifyApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app.repository.mainRepository) as T
        }
        ViewModelProvider(this, factory)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 16 (API 36) migration: switch to `enableEdgeToEdge()` from
        // androidx.activity:activity-ktx 1.9+. Handles system-bar scrim +
        // transparent navigation contrast automatically; equivalent to
        // `WindowCompat.setDecorFitsSystemWindows(window, false)` plus
        // manual isAppearanceLightStatusBars wiring.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = sysBars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainToolbar) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sysBars.top)
            insets
        }

        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        // Sportzfy top bar buttons — haptic feedback + Material3 ripple.
        binding.btnDrawerToggle.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.drawerRoot.openDrawer(GravityCompat.START)
        }
        binding.btnToolbarSearch.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            navController.navigate(R.id.searchFragment)
        }
        binding.btnToolbarFavorites.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            navController.navigate(R.id.favoritesFragment)
        }
        binding.btnToolbarRefresh.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            supportFragmentManager.fragments
                .filterIsInstance<com.streamify.app.ui.fragments.HomeFragment>()
                .firstOrNull()?.refresh()
        }
        binding.btnToolbarNetwork.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            navController.navigate(R.id.networkStreamFragment)
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.drawer_playlists -> {
                    navController.navigate(R.id.playlistsFragment)
                    binding.drawerRoot.closeDrawers()
                    true
                }
                R.id.drawer_network_stream -> {
                    navController.navigate(R.id.networkStreamFragment)
                    binding.drawerRoot.closeDrawers()
                    true
                }
                R.id.drawer_notice -> {
                    navController.navigate(R.id.noticeFragment)
                    binding.drawerRoot.closeDrawers()
                    true
                }
                R.id.drawer_update -> {
                    binding.drawerRoot.closeDrawers()
                    runUpdateFlowFromDrawer()
                    true
                }
                R.id.drawer_share -> {
                    binding.drawerRoot.closeDrawers()
                    shareAppLink()
                    true
                }
                R.id.drawer_exit -> {
                    binding.drawerRoot.closeDrawers()
                    finishAffinity()
                    true
                }
                else -> {
                    val msg = getString(
                        R.string.drawer_coming_soon_template,
                        item.title.toString(),
                        upcomingStepFor(item.itemId)
                    )
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT)
                        .setAnchorView(binding.bottomNav)
                        .show()
                    binding.drawerRoot.closeDrawers()
                    true
                }
            }
        }

        if (mainVm.state.value is UiState.Idle) {
            mainVm.load()
        }

        // Android 16 (API 36) migration · v1.1.0 — silent forced-logout
        // UX cue. See SecurityPrefs.kt + strings.xml (reauth_v11_*) for
        // the rationale. Two code-review invariants enforced here:
        //   (1) write-then-show: markV11ReauthSeen() runs BEFORE the
        //       dialog is shown so a process-death or Activity-STOP
        //       between them cannot re-prompt the user on next launch.
        //   (2) inside repeatOnLifecycle(STARTED): the suspending
        //       DataStore read+write runs in one continuous STARTED
        //       window so an Activity STOP cannot cancel the write
        //       mid-flight.  CancellationException is rethrown so
        //       structured concurrency survives; the catch-all is
        //       Log.w-only so a corrupted DataStore blob can never
        //       block app launch.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    val app = application as StreamifyApp
                    if (!app.tokenManager.isLoggedIn() &&
                        !app.securityPrefs.hasSeenV11Reauth()
                    ) {
                        // Tighten the race window: `repeatOnLifecycle(STARTED)`
                        // cancels its coroutine on Activity STOP so a user
                        // backgrounding the app between this suspending
                        // read+write could cancel `markSeen()` mid-flight
                        // and re-prompt them on next launch. Wrapping the
                        // DataStore write in NonCancellable guarantees the
                        // flag flip either completes or throws — never
                        // silently dropped. `showReAuthV11Dialog()` stays
                        // OUTSIDE the wrap so Activity-STOP can still
                        // dismiss the dialog naturally.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            app.securityPrefs.markV11ReauthSeen()
                        }
                        showReAuthV11Dialog()
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w(TAG, "v1.1.0 re-auth explanation check failed", t)
                }
                mainVm.state.collectLatest { state ->
                    if (state is UiState.Error) {
                        Snackbar.make(binding.root, state.message, Snackbar.LENGTH_INDEFINITE)
                            .setAnchorView(binding.bottomNav)
                            .setAction(getString(R.string.splash_retry)) { mainVm.retry() }
                            .show()
                    }
                }
            }
        }
    }

    /**
     * Map a placeholder Drawer item id to the plan-listed step where its
     * real screen ships. drawer_update was removed from this map in
     * Phase 6 · Step 6.2 because the update flow is now live.
     */
    private fun upcomingStepFor(itemId: Int): String = when (itemId) {
        R.id.drawer_floating_player  -> "4.6"
        R.id.drawer_video_quality    -> "4.5"
        R.id.drawer_join_us          -> "8.x"
        else                          -> "TBD"
    }

    /**
     * Phase 6 · Step 6.2 — Drawer "Update" handler.
     *
     * Re-runs the splash's [UpdateDecision] check inside the Activity
     * scope (Drawer taps happen any time, not just Cold Start) and routes
     * to the appropriate UX:
     *   - Forced   → UpdateActivity (rare — splash gate normally catches)
     *   - Optional → UpdateDialogFragment.newOptional(...) via
     *                FragmentManager.showUpdateDialog
     *   - UpToDate → Snackbar "Up to date"
     *   - Failed   → Snackbar with the reason
     *
     * `updateManager.checkForUpdate()` runs on Dispatchers.IO and is
     * CancellationException-safe.
     */
    private fun runUpdateFlowFromDrawer() {
        val app = application as StreamifyApp
        lifecycleScope.launch {
            val decision = try {
                app.updateManager.shouldShowOptionalNag() ?: app.updateManager.checkForUpdate()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                UpdateDecision.Failed(e.message ?: e.javaClass.simpleName)
            }

            when (decision) {
                is UpdateDecision.Forced -> {
                    UpdateActivity.startForced(
                        context = this@MainActivity,
                        minVersion = decision.minVersion,
                        apkUrl = decision.apkUrl
                    )
                }
                is UpdateDecision.Optional -> {
                    val frag = UpdateDialogFragment.newOptional(
                        latest = decision.latestVersion,
                        apkUrl = decision.apkUrl,
                        changelog = decision.changelog
                    )
                    supportFragmentManager.showUpdateDialog(frag)
                }
                is UpdateDecision.UpToDate -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.update_up_to_date),
                        Snackbar.LENGTH_SHORT
                    ).setAnchorView(binding.bottomNav).show()
                }
                is UpdateDecision.Failed -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.update_check_failed, decision.reason),
                        Snackbar.LENGTH_SHORT
                    ).setAnchorView(binding.bottomNav).show()
                }
            }
        }
    }

    private fun shareAppLink() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.drawer_share_message))
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.drawer_share)))
    }

    /**
     * Android 16 (API 36) migration · v1.1.0 — Material You re-auth
     * explanation. Shown exactly once per install to users whose
     * `streamify_secure_prefs` (EncryptedSharedPreferences) refresh
     * token is no longer readable because
     * `androidx.security:security-crypto` 1.1.0-alpha06 was removed
     * in this migration. The OK button just dismisses; the
     * underlying MainActivity continues unchanged (logged-out users
     * are routed to the standard login flow by the rest of the app).
     */
    private fun showReAuthV11Dialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reauth_v11_title)
            .setMessage(R.string.reauth_v11_body)
            .setCancelable(false)
            .setPositiveButton(R.string.reauth_v11_dismiss) { d, _ -> d.dismiss() }
            .show()
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
