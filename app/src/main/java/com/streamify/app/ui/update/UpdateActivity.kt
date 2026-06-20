package com.streamify.app.ui.update

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.streamify.app.R

/**
 * Phase 6 · Step 6.2 — Forced Update gate.
 *
 * The splash routes here when [com.streamify.app.data.update.UpdateDecision.Forced]
 * comes back from [AppUpdateManager.checkForUpdate]. This activity:
 *  - hosts [UpdateDialogFragment] in forced mode,
 *  - intercepts Back &rarr; finishes the entire task (the user is
 *    forbidden from skipping the update),
 *  - excludes itself from recents so the user can't swipe-away back to
 *    a stale launcher icon.
 *
 * Layout contract: `R.layout.activity_update` is just a
 * `FragmentContainerView` placeholder — the dialog renders the rest.
 *
 * Opaque theme (NOT transparent — thinker-gemini step-6.2 point F flagged
 * transparent activities for accidental touch-through to underlying
 * content).
 */
class UpdateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        if (savedInstanceState == null) {
            val min = intent.getStringExtra(EXTRA_MIN).orEmpty()
            val url = intent.getStringExtra(EXTRA_APK_URL).orEmpty()
            val fragment = UpdateDialogFragment.newForced(min = min, apkUrl = url)
            supportFragmentManager.beginTransaction()
                .replace(R.id.updateContainer, fragment)
                .commitNow()
        }

        // Back button &rarr; forced-exit so the user can't bypass the gate.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishAffinity()
                }
            }
        )
    }

    companion object {
        const val EXTRA_MIN = "extra.min"
        const val EXTRA_APK_URL = "extra.apk_url"

        fun startForced(context: Context, minVersion: String, apkUrl: String) {
            val i = Intent(context, UpdateActivity::class.java)
                .putExtra(EXTRA_MIN, minVersion)
                .putExtra(EXTRA_APK_URL, apkUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(i)
        }
    }
}
