package com.streamify.admin.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    lateinit var api: AdminApi
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = applicationContext as StreamifyAdminApp
        api = AdminApi(
            baseUrl = StreamifyAdminApp.ADMIN_API_BASE_URL,
            httpClient = app.httpClient,
            tokenProvider = { app.adminToken }
        )

        binding.tvTitle.text = "Admin · ${app.adminName}"
        binding.tvRole.text = app.adminRole

        // Load dashboard stats
        loadStats()

        // Section buttons
        binding.btnEvents.setOnClickListener { showFragment("events") }
        binding.btnChannels.setOnClickListener { showFragment("channels") }
        binding.btnHighlights.setOnClickListener { showFragment("highlights") }
        binding.btnCategories.setOnClickListener { showFragment("categories") }
        binding.btnConfig.setOnClickListener { showFragment("config") }
        binding.btnNotifications.setOnClickListener { showFragment("notifications") }
        binding.btnLogout.setOnClickListener {
            app.adminToken = ""
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Default: events
        showFragment("events")
    }

    private fun loadStats() {
        lifecycleScope.launch {
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
