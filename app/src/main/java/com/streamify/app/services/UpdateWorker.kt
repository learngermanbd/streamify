package com.streamify.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.update.UpdateDecision
import com.streamify.app.ui.activities.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Phase 6 · Step 6.2 — Daily background update check.
 *
 * Runs once every ~24 hours via [WorkManager]. If the server reports
 * a [UpdateDecision.Forced] or [UpdateDecision.Optional], a local
 * notification is posted so the user knows an update is available even
 * if they haven't opened the app today.
 *
 * WorkManager guarantees:
 *  - Battery-aware scheduling (defers on Doze / App Standby).
 *  - Survives process death and reboot.
 *  - Coalesces duplicate registrations via
 *    [EXISTING_PERIODIC_WORK_POLICY_KEEP].
 *
 * The worker is enqueued from [StreamifyApp.onCreate] exactly once;
 * subsequent cold starts use KEEP so the existing schedule is preserved.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "daily_update_check"
        private const val CHANNEL_ID = "streamify_updates"
        private const val NOTIFICATION_ID = 9001

        /**
         * Enqueue the periodic work. Idempotent — calling again with
         * KEEP preserves the existing schedule.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(
                1, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic update check enqueued (policy=KEEP)")
        }
    }

    override suspend fun doWork(): Result {
        val app = try {
            applicationContext as StreamifyApp
        } catch (e: ClassCastException) {
            Log.w(TAG, "Not a StreamifyApp context; skipping")
            return Result.failure()
        }

        // checkForUpdate() already catches all non-CancellationException
        // throwables internally and returns UpdateDecision.Failed. The outer
        // try/catch is therefore unnecessary — let the decision flow through.
        val decision = app.updateManager.checkForUpdate()

        when (decision) {
            is UpdateDecision.Forced -> {
                postNotification(
                    title = applicationContext.getString(
                        R.string.update_notification_title
                    ),
                    body = applicationContext.getString(
                        R.string.update_forced_title, decision.minVersion
                    )
                )
            }
            is UpdateDecision.Optional -> {
                // Only nag if the user hasn't already dismissed this version.
                val dismissed = app.updatePrefs.readDismissedLatest()
                if (dismissed != decision.latestVersion) {
                    postNotification(
                        title = applicationContext.getString(
                            R.string.update_notification_title
                        ),
                        body = applicationContext.getString(
                            R.string.update_optional_title, decision.latestVersion
                        )
                    )
                }
            }
            is UpdateDecision.UpToDate -> {
                Log.d(TAG, "Up to date — no notification")
            }
            is UpdateDecision.Failed -> {
                Log.w(TAG, "Update check failed: ${decision.reason}")
                // Don't retry on parse/config failures — only on network errors.
            }
        }
        return Result.success()
    }

    private fun postNotification(title: String, body: String) {
        ensureChannel()
        // Tapping the notification opens MainActivity which triggers
        // the update dialog via its existing shouldShowOptionalNag / checkForUpdate flow.
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = applicationContext.getString(R.string.update_channel_description)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
