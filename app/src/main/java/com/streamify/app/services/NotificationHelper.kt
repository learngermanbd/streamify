package com.streamify.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.streamify.app.R

/**
 * Phase 5 · Step 5.4 — Push notification surface.
 *
 * Creates the **Live Events** channel (HIGH importance so DND-permitting
 * users still get a heads-up when a match goes live) plus a fallback
 * **General** channel for non-live pushes. `ensureChannel` is idempotent
 * — the OS no-ops on re-create with identical IDs, so calling it from
 * every cold-start path is cheap.
 *
 * No-op on API < 26 (no notification channels then; the OS uses the
 * notification's `priority` field instead, which our builder still sets
 * for older devices — graceful degradation).
 *
 * The actual deep-link routing when the user taps a notification is
 * handled by Android's `Intent.ACTION_VIEW` against the NotificationCompat
 * builder's contentIntent. The deep-link URI format (`streamify://...`)
 * is decided by Phase 8 when the backend's notification composer lands;
 * for v1 the helper just ships the URI through unmodified.
 */
object NotificationHelper {

    const val CHANNEL_ID_LIVE_EVENTS = "live_events"
    const val CHANNEL_ID_GENERAL = "general"

    private const val LIVE_EVENTS_NAME = "Live Events"
    private const val LIVE_EVENTS_DESC =
        "Heads-up notifications when a match goes live or a favorite team scores."

    private const val GENERAL_NAME = "General"
    private const val GENERAL_DESC = "App announcements + maintenance notices."

    /**
     * Create the channel(s) if missing. Idempotent. Call from
     * [StreamifyApp.onCreate] AND from [StreamifyMessagingService.onCreate]
     * so the channel exists before any push arrives.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val live = NotificationChannel(
            CHANNEL_ID_LIVE_EVENTS,
            LIVE_EVENTS_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = LIVE_EVENTS_DESC
            enableLights(true)
            lightColor = Color.parseColor("#1CCBD4")
            setShowBadge(true)
        }
        nm.createNotificationChannel(live)

        val general = NotificationChannel(
            CHANNEL_ID_GENERAL,
            GENERAL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = GENERAL_DESC
            setShowBadge(true)
        }
        nm.createNotificationChannel(general)
    }

    /**
     * Render a Live-Event notification with a deep-link tap target.
     *
     * @param notificationId Stable per-event id so a re-push collapses
     *                       instead of stacking (Phase 8 backend mirrors
     *                       this for re-engagement sends).
     * @param title          Push title (server-supplied).
     * @param body           Push body (server-supplied).
     * @param deepLinkUri    Optional `streamify://...` deep link. When
     *                       null, taps open MainActivity.
     */
    fun showLiveEventNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        deepLinkUri: Uri? = null
    ) {
        ensureChannels(context)

        val tapIntent = Intent(Intent.ACTION_VIEW).apply {
            data = deepLinkUri ?: Uri.parse("streamify://main")
            `package` = context.packageName
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(context, notificationId, tapIntent, pendingFlags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())

        // Permission check — POST_NOTIFICATIONS is runtime on API 33+.
        // NotificationManagerCompat.notify silently no-ops if the user
        // denied the runtime permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, "android.permission.POST_NOTIFICATIONS"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}
