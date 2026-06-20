package com.streamify.app.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.streamify.app.R
import com.streamify.app.data.crash.CrashHandler
import com.streamify.app.databinding.ActivityCrashBinding
import com.streamify.app.ui.activities.MainActivity

/**
 * Phase 6 · Step 6.4 — Crash recovery UI.
 *
 * Launched from
 * [com.streamify.app.data.crash.CrashHandler] when an unhandled
 * exception terminates the app's UI thread. Mirrors the UpdateActivity
 * forced-update gate pattern (Phase 6 · Step 6.2):
 *
 *   - `android:taskAffinity=""`              → leaves the doomed main
 *     task instead of inheriting it, so the back-stack doesn't restart
 *     the crashed process.
 *   - `android:excludeFromRecents="true"`     → doesn't pollute the
 *     Recent tasks list; the next user-visible Activity is the
 *     MainActivity the Restart button launches.
 *   - `android:process=":crash"`             → runs in an isolated
 *     process so a failure inside
 *     [com.streamify.app.StreamifyApp.onCreate] cannot prevent
 *     this Activity from ever spawning.
 *
 * Layout: a single MaterialCardView (live-red border) with title +
 * subtitle + body copy, plus the raw dump text in a `TextView` for
 * advanced users. Two actions: Restart (Cold-launch MainActivity) +
 * Copy details (ClipboardManager).
 */
class CrashActivity : AppCompatActivity() {

    private var _binding: ActivityCrashBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderReport()
        wireActions()
    }

    private fun renderReport() {
        val report = CrashHandler.readLatestCrashReport(this)
        if (report.isNullOrBlank()) {
            // Either nothing on disk yet OR dump write failed mid-handler.
            // Show only the body copy so the user can still hit Restart.
            binding.crashSubtitle.text = ""
            binding.crashReportText.visibility = View.GONE
            return
        }
        // Subtitle = first-line summary of the dump. Pull "App version" 
        // and "Timestamp" lines for the user-visible header.
        val subtitle = report
            .lineSequence()
            .firstOrNull { it.startsWith("Timestamp:") }
            ?.removePrefix("Timestamp:")
            ?.trim()
            .orEmpty()
        binding.crashSubtitle.text = subtitle
        binding.crashReportText.text = report
        binding.crashReportText.visibility = View.VISIBLE
    }

    private fun wireActions() {
        binding.crashRestartButton.setOnClickListener {
            // Cold-launch MainActivity so the entire graph rebuilds and
            // any lingering state from the dying process is gone. The
            // CLEAR_TASK flag on this Intent already destroys everything
            // else on the task — including CrashActivity itself — so we
            // only need [finish] to drop *this* Activity out of the 
            // window-list once the launch is in flight. (finishAffinity 
            // races the Intent-dispatch because it removes the entire 
            // task from the Activity stack while the Intent is still 
            // being routed to the dying main process.)
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: Throwable) {
                // Worst case the user can relaunch from the launcher.
            }
            finish()
        }

        binding.crashCopyButton.setOnClickListener {
            val report = CrashHandler.readLatestCrashReport(this) ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("SportStream crash report", report)
            )
            Snackbar.make(
                binding.root,
                R.string.crash_copied_to_clipboard,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        /** Tag marker so CrashHandler can signal "started from crash path". */
        const val EXTRA_FROM_CRASH = "extra_from_crash"
    }
}
