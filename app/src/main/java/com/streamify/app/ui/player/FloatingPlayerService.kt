package com.streamify.app.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamify.app.R
import com.streamify.app.StreamifyApp
import com.streamify.app.data.prefs.PlayerPrefs
import com.streamify.app.ui.activities.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phase 4 · Step 4.6 — Floating player service.
 *
 * Architecture (settled per design round):
 *  - Plain `Service` (not MediaSessionService) for full control over
 *    foreground promotion timing. We must be foreground BEFORE calling
 *    WindowManager.addView so Android doesn't kill the process during
 *    the surface-attach + ExoPlayer.prepare dance.
 *  - Service owns its OWN ExoPlayer + PlayerView, separate from
 *    PlayerActivity's. PlayerView's Surface lifecycle is tied to its
 *    window's Surface; sharing the player across two PlayerViews would
 *    force PixelCopy or surface handoff, vastly more complex than the
 *    standard "detach = release A, start B at saved position" pattern.
 *  - Steps:
 *      1. PlayerActivity.btnDetach tapped.
 *      2. PlayerActivity pauses + releases its ExoPlayer, captures URL
 *         + positionMs + title + subtitle extras.
 *      3. PlayerActivity checks Settings.canDrawOverlays. If denied
 *         → ACTION_MANAGE_OVERLAY_PERMISSION intent + finish().
 *         Else → FloatingPlayerService.launchStart(...).
 *      4. Service.onStartCommand builds its own ExoPlayer at saved
 *         position, calls startForeground(MEDIA_PLAYBACK) on API 34+,
 *         then WindowManager.addView(TYPE_APPLICATION_OVERLAY).
 *  - Drag math: View.OnTouchListener on the FrameLayout container.
 *      ACTION_DOWN: capture rawX/rawY + current params.x/y.
 *      ACTION_MOVE: rewrite params.x/y clamped to screen edges.
 *                   activate drag-mode after 8 px slop to avoid stealing
 *                   single-tap (toggle play/pause).
 *      ACTION_UP: if dragging, snap to nearest horizontal edge + persist
 *                 xRatio/yRatio to PlayerPrefs (resolution-independent).
 *                 else, toggle play/pause.
 *  - WindowManager.LayoutParams uses TYPE_APPLICATION_OVERLAY on API 26+
 *    (TYPE_PHONE on older devices, deprecated path).
 *  - Close X calls tearDown() + stopSelf().
 *
 * Manifest side:
 *  - <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 *  - <service android:name=".ui.player.FloatingPlayerService"
 *             android:exported="false"
 *             android:foregroundServiceType="mediaPlayback" />
 *  - FOREGROUND_SERVICE_MEDIA_PLAYBACK is already declared in Step 4.2.
 *
 * Strict-plan deviations (logged in TODO after this turn):
 *  - MediaSessionService auto-promote is not used; manual
 *    startForeground keeps notification + foreground promotion decoupled.
 *  - No MediaSession: notification only ships Stop button. System media
 *    transport controls (lock screen / notification shade) are not
 *    landed — they'll come with a future Step 4.7 polish if needed.
 */
class FloatingPlayerService : Service() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private var dragStartRawX: Float = 0f
    private var dragStartRawY: Float = 0f
    private var dragStartParamsX: Int = 0
    private var dragStartParamsY: Int = 0
    private var dragging: Boolean = false

    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val prefs by lazy { PlayerPrefs(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                tearDown()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        if (url.isBlank()) {
            Log.w(TAG, "ACTION_START with empty url — stopping")
            stopSelf()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
        val subUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL).orEmpty()
        val subLang = intent.getStringExtra(EXTRA_SUBTITLE_LANG)

        // 1. Foreground promote IMMEDIATELY so the OS doesn't kill us
        //    during the WindowManager.addView + ExoPlayer.prepare dance.
        val notification = buildNotification(title, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }

        // 2. Build (or reuse) ExoPlayer.
        ensurePlayer(url, title, positionMs, subUrl, subLang)

        // 3. Add (or reuse) overlay window.
        ensureOverlay()
    }

    private fun ensurePlayer(
        url: String,
        title: String,
        positionMs: Long,
        subUrl: String,
        subLang: String?
    ) {
        if (player != null) {
            applyMediaItem(url, title, subUrl, subLang)
            player?.seekTo(positionMs.coerceAtLeast(0L))
            return
        }
        val app = applicationContext as StreamifyApp
        val httpClient = app.network.httpClient as okhttp3.OkHttpClient
        val msFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(httpClient))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(msFactory)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { p ->
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus */ true
                )
                applyMediaItem(p, url, title, subUrl, subLang)
                p.seekTo(positionMs.coerceAtLeast(0L))
                p.playWhenReady = true
                p.addListener(playerListener)
                p.prepare()
            }
    }

    private fun applyMediaItem(
        player: ExoPlayer,
        url: String,
        title: String,
        subUrl: String,
        subLang: String?
    ) {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (subUrl.isNotBlank()) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setMimeType(detectSubtitleMime(subUrl))
                        .setLanguage(subLang ?: "en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        player.setMediaItem(builder.build())
    }

    private fun applyMediaItem(url: String, title: String, subUrl: String, subLang: String?) {
        player?.let { applyMediaItem(it, url, title, subUrl, subLang) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNotificationState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(
                TAG,
                "Player error: " + (error.localizedMessage ?: error.errorCodeName)
            )
        }
    }

    private fun updateNotificationState() {
        // Best-effort refresh so the notification title/icon stays in sync
        // when the user switches media items. Cheap (one getSystemService
        // + one notify) — runs on every isPlaying change so players
        // moving paused→playing / playing→idle still see correct chrome.
        val p = player ?: return
        val title = p.mediaMetadata.title?.toString().orEmpty()
        val url = p.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        val notification = buildNotification(title, url)
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification)
        }
    }

    private fun ensureOverlay() {
        if (overlayView != null) return

        val displayMetrics = resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.55f).toInt()
            .coerceIn(
                dp(220),
                (displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(220))
            )
        val overlayHeight = (overlayWidth * 9f / 16f).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(overlayWidth, overlayHeight)
            setBackgroundResource(R.drawable.bg_floating_player)
            elevation = dp(8).toFloat()
        }

        val surfaceView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            this.player = this@FloatingPlayerService.player
        }
        container.addView(surfaceView)
        playerView = surfaceView

        val closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_floating_close)
            setBackgroundResource(R.drawable.bg_floating_close)
            contentDescription = getString(R.string.player_floating_close_desc)
            val lp = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            layoutParams = lp
            setOnClickListener {
                tearDown()
                stopSelf()
            }
        }
        container.addView(closeButton)

        attachDragListener(container)
        overlayView = container

        serviceScope.launch {
            val xRatio = prefs.floatingPlayerXRatioFlow.first()
            val yRatio = prefs.floatingPlayerYRatioFlow.first()
            val lp = buildOverlayLayoutParams(overlayWidth, overlayHeight, xRatio, yRatio)
            val wm = windowManager
            if (wm == null) {
                Log.w(TAG, "WindowManager unavailable; cannot add overlay")
                stopSelf()
                return@launch
            }
            runCatching {
                wm.addView(container, lp)
                currentLayoutParams = lp
            }.onFailure { e ->
                Log.w(TAG, "WindowManager.addView failed: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun attachDragListener(view: View) {
        view.setOnTouchListener { _, event ->
            val params = currentLayoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val displayMetrics = resources.displayMetrics
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartParamsX = params.x
                    dragStartParamsY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragging && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                        dragging = true
                    }
                    if (dragging) {
                        val newX = (dragStartParamsX + dx.toInt())
                            .coerceIn(0, (displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                        val newY = (dragStartParamsY + dy.toInt())
                            .coerceIn(0, (displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                        params.x = newX
                        params.y = newY
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val midX = displayMetrics.widthPixels / 2
                        params.x = if (params.x + view.width / 2 < midX) 0
                            else (displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(view, params) }
                        saveOverlayPosition(params, view.width, view.height, displayMetrics)
                    } else {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun buildOverlayLayoutParams(
        overlayWidth: Int,
        overlayHeight: Int,
        xRatio: Float,
        yRatio: Float
    ): WindowManager.LayoutParams {
        val displayMetrics = resources.displayMetrics
        val maxX = (displayMetrics.widthPixels - overlayWidth).coerceAtLeast(0)
        val maxY = (displayMetrics.heightPixels - overlayHeight).coerceAtLeast(0)
        val x = (xRatio * maxX).toInt().coerceIn(0, maxX)
        val y = (yRatio * maxY).toInt().coerceIn(0, maxY)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun saveOverlayPosition(
        params: WindowManager.LayoutParams,
        viewWidth: Int,
        viewHeight: Int,
        displayMetrics: android.util.DisplayMetrics
    ) {
        val maxX = (displayMetrics.widthPixels - viewWidth).coerceAtLeast(1)
        val maxY = (displayMetrics.heightPixels - viewHeight).coerceAtLeast(1)
        val xRatio = (params.x.toFloat() / maxX.toFloat()).coerceIn(0f, 1f)
        val yRatio = (params.y.toFloat() / maxY.toFloat()).coerceIn(0f, 1f)
        serviceScope.launch {
            runCatching {
                prefs.setFloatingPlayerPosition(xRatio, yRatio)
            }
        }
    }

    private fun tearDown() {
        overlayView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        overlayView = null
        playerView = null
        currentLayoutParams = null
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        tearDown()
        player?.let { p ->
            p.removeListener(playerListener)
            p.release()
        }
        player = null
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.player_floating_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, url: String): Notification {
        val openIntent = Intent(applicationContext, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
        }
        val openPi = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(applicationContext, FloatingPlayerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            applicationContext, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title.ifBlank { getString(R.string.player_floating_default_title) })
            .setContentText(getString(R.string.player_floating_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.player_floating_action_stop),
                stopPi
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun detectSubtitleMime(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            lower.endsWith(".ttml") || lower.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> {
                Log.w(TAG, "Unknown subtitle MIME for $url — best-effort VTT")
                MimeTypes.TEXT_VTT
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val TAG = "FloatingPlayerService"

        const val ACTION_START = "com.streamify.app.action.FLOATING_START"
        const val ACTION_STOP = "com.streamify.app.action.FLOATING_STOP"

        const val EXTRA_VIDEO_URL = "videoUrl"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_SUBTITLE_URL = "subtitleUrl"
        const val EXTRA_SUBTITLE_LANG = "subtitleLang"

        const val CHANNEL_ID = "floating_player"
        const val NOTIF_ID = 4242

        /**
         * Public entry point. PlayerActivity calls this after CAN_DRAW_OVERLAYS check.
         * pre-API-26 callers fall back to startService; API 26+ must go via
         * startForegroundService because the service starts a foreground task.
         */
        fun launchStart(
            context: Context,
            videoUrl: String,
            title: String,
            positionMs: Long,
            subtitleUrl: String? = null,
            subtitleLang: String? = null
        ) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSITION_MS, positionMs)
                if (subtitleUrl != null) putExtra(EXTRA_SUBTITLE_URL, subtitleUrl)
                if (subtitleLang != null) putExtra(EXTRA_SUBTITLE_LANG, subtitleLang)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun launchStop(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
