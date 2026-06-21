package com.streamify.admin.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.streamify.admin.StreamifyAdminApp
import com.streamify.admin.data.AdminApi
import com.streamify.admin.databinding.ActivityDashboardBinding
import com.streamify.admin.ui.login.LoginActivity
import com.streamify.admin.ui.events.EventsFragment
import com.streamify.admin.ui.channels.ChannelsFragment
import com.streamify.admin.ui.highlights.HighlightsFragment
import com.streamify.admin.ui.categories.CategoriesFragment
import com.streamify.admin.ui.config.ConfigFragment
import com.streamify.admin.ui.notifications.NotificationsFragment
import kotlinx.coroutines.launch

/**
 * Phase 8 · Step 8.14-8.15 — Admin Dashboard with fragment-based management.
 *
 * Toolbar buttons switch between management sections:
 * Events, Channels, Highlights, Categories, Config, Notifications.
 * The AdminApi singleton is wired through the Application class token.
 *
 * post-v1.1.0 fixes:
 *  - Empty-token gatekeeper: if app.adminToken is blank, bounce to login.
 *  - Logout now calls StreamifyAdminApp.clearSession so the DataStore
 *    entry is wiped too.
 *  - loadStats now runs inside repeatOnLifecycle(STARTED) so a rotation
 *    cancels the in-flight analytics request instantly instead of waiting
 *    for it to time out (we use a 60s callTimeout).
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    lateinit var api: AdminApi
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = applicationContext as StreamifyAdminApp

        // post-v1.1.0 — empty-token gatekeeper. Check BEFORE setContentView
        // so a session-wiped user never sees a millisecond flash of the
        // dashboard layout before being bounced back to login.
        if (app.adminToken.isBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        api = AdminApi(
            baseUrl = StreamifyAdminApp.ADMIN_API_BASE_URL,
            httpClient = app.httpClient,
            tokenProvider = { app.adminToken }
        )

        binding.tvTitle.text = "Admin · ${app.adminName}"
        binding.tvRole.text = app.adminRole

        // Load dashboard stats (cancel-on-stop so rotations do not peg the UI)
        loadStats()

        // Section buttons
        binding.btnEvents.setOnClickListener { showFragment("events") }
        binding.btnChannels.setOnClickListener { showFragment("channels") }
        binding.btnHighlights.setOnClickListener { showFragment("highlights") }
        binding.btnCategories.setOnClickListener { showFragment("categories") }
        binding.btnConfig.setOnClickListener { showFragment("config") }
        binding.btnNotifications.setOnClickListener { showFragment("notifications") }
        binding.btnLogout.setOnClickListener { logout() }

        // Default: events
        showFragment("events")
    }

    private fun logout() {
        val app = applicationContext as StreamifyAdminApp
        // post-v1.1.0 review fix — wipe in-memory FIRST so LoginActivity's
        // launchAutoLogin sees adminToken.isBlank() and bails out before the
        // DataStore write even starts. Without this, the `clearSessionInBackground`
        // dispatch races with LoginActivity.launchAutoLogin's loadSession read
        // and the auto-login can resurrect the just-logged-out admin.
        app.adminToken        = ""
        app.adminRefreshToken = ""
        app.adminName         = "Admin"
        app.adminRole         = "EDITOR"
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        app.clearSessionInBackground()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (val result = api.getAnalytics()) {
                    is AdminApi.ApiResult.Success -> {
                        val o = result.data
                        val liveEvents = o.optJSONObject("overview")?.optInt("liveEvents", 0) ?: 0
                        val totalEvents = o.optJSONObject("overview")?.optInt("totalEvents", 0) ?: 0
                        val totalChannels = o.optJSONObject("overview")?.optInt("totalChannels", 0) ?: 0
                        binding.tvStats.text = "Live: $liveEvents | Events: $totalEvents | Channels: $totalChannels"
                    }
                    is AdminApi.ApiResult.Failure -> {
                        binding.tvStats.text = "Could not load stats"
                    }
                }
            }
        }
    }

    private fun showFragment(name: String) {
        // Reset button styles
        listOf(binding.btnEvents, binding.btnChannels, binding.btnHighlights,
            binding.btnCategories, binding.btnConfig, binding.btnNotifications).forEach {
            it.isSelected = false
        }

        val fragment: Fragment = when (name) {
            "events" -> { binding.btnEvents.isSelected = true; EventsFragment() }
            "channels" -> { binding.btnChannels.isSelected = true; ChannelsFragment() }
            "highlights" -> { binding.btnHighlights.isSelected = true; HighlightsFragment() }
            "categories" -> { binding.btnCategories.isSelected = true; CategoriesFragment() }
            "config" -> { binding.btnConfig.isSelected = true; ConfigFragment() }
            "notifications" -> { binding.btnNotifications.isSelected = true; NotificationsFragment() }
            else -> EventsFragment()
        }

        supportFragmentManager.beginTransaction()
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
