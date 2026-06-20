package com.streamify.app.services

import android.net.Uri
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.streamify.app.StreamifyApp
import com.streamify.app.data.models.Notice
import com.streamify.app.data.models.NoticeAttachment
import com.streamify.app.data.models.NoticeSection
import com.streamify.app.data.repository.NoticeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 5 · Step 5.4 — FCM push recipient.
 *
 * Lifecycle hooks the plan calls out:
 *
 *  - [onNewToken]     — fires on first install AND on token rotation.
 *                       Posts the token to the admin API at
 *                       `POST /api/fcm/register` so the backend can
 *                       target this device. Failures are logged but
 *                       non-fatal — the next rotation retries.
 *
 *  - [onMessageReceived] — data messages (server payload includes
 *                       `title`, `body`, `deepLink`, optional `notificationId`).
 *                       Notification messages w/o data also fire here;
 *                       fallback to `getNotification()` so default FCM
 *                       rendering works on API 24+ before channels.
 *
 *  - [onDeletedMessages] — FCM tells us it dropped >20 messages; we
 *                       should re-sync from `GET /api/notifications`
 *                       (lands with the admin backend in Phase 8).
 *
 * The Service runs in its own process-thread via Firebase's
 * messaging intent-filter (manifest-declared), so we use a
 * SupervisorJob-backed scope to keep a single failed API call from
 * cancelling any in-flight delivery.
 *
 * Phase 5 · Step 5.7 harden: notice-sync hot-guards the access to
 * `(applicationContext as StreamifyApp).repository.noticeRepository`
 * so a Phase 6 async-init re-ordering can't crash silently.
 */
class StreamifyMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Channel-pre-create so any FCM push that arrives while the user
        // is mid-launch renders with the right vibration/LED profile.
        NotificationHelper.ensureChannels(applicationContext)
    }

    /**
     * First install + rotation: post the token to the admin backend.
     * The endpoint shape is `{ "token": "...", "platform": "android" }`;
     * the backend maps token → user-id → targeted push later.
     *
     * We do NOT log raw token bytes — Sentry breadcrumbs and adb logcat
     * both leak to anyone with USB debugging access. Only the rotation
     * timestamp + length are recorded.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCMService", "New FCM token: rotation accepted, len=${token.length}")
        scope.launch { postTokenToBackend(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Phase 5 v2 · Step 5.5 — branch on the `type` discriminator.
        // `type == "notice"` payload is a v2 NoticeEntry; we route to
        // handleNoticePayload which (a) persists to Room so the
        // NoticeFragment Flow auto-re-emits, and (b) ALSO surfaces the
        // OS-level Live Events banner so users with the app in the
        // background get a heads-up. For legacy live-event pushes,
        // the previous v1 code path runs unchanged.
        if (data["type"]?.equals("notice", ignoreCase = true) == true) {
            handleNoticePayload(message)
            return
        }

        // Prefer data payload (we control the format); fall back to the
        // OS-level notification if data is missing.
        val title = data["title"]
            ?: message.notification?.title
            ?: getString(com.streamify.app.R.string.notification_default_title)
        val body = data["body"]
            ?: message.notification?.body
            ?: getString(com.streamify.app.R.string.notification_default_body)
        val deepLinkRaw = data["deepLink"]
        val deepLink: Uri? = deepLinkRaw?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val notifId = data["notificationId"]?.toIntOrNull()
            ?: (System.currentTimeMillis() and 0x7FFFFFFF).toInt()

        NotificationHelper.showLiveEventNotification(
            context = applicationContext,
            notificationId = notifId,
            title = title,
            body = body,
            deepLinkUri = deepLink
        )
    }

    /**
     * Phase 5 v2 · Step 5.5 — handle a `type=notice` push payload.
     *
     * Payload shape:
     *  {
     *      "type": "notice",
     *      "id": "push:abc123",       // optional; falls back to FCM messageId
     *      "title": "Server downtime",
     *      "body":  "Sunday 02:00 UTC",
     *      "section": "ALERT",        // INFO / PROMO / ALERT
     *      "priority": "1",           // optional int
     *      "expiresAt": "1731235200", // optional epoch millis string
     *      "attachments": "[{\"url\":\"...\",\"type\":\"LINK\"}]" // optional JSON string
     *  }
     *
     * Two parallel side-effects:
     *  1. Persist to Room via [NoticeRepository.insertPushSourced].
     *     This makes the NoticeFragment observe-and-render the new item
     *     without a tap on the FAB.
     *  2. Show OS-level notification via [NotificationHelper] so the
     *     user sees the alert in the status bar even if NoticeFragment
     *     isn't currently visible.
     */
    private fun handleNoticePayload(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: getString(com.streamify.app.R.string.notification_default_title)
        val body  = data["body"] ?: getString(com.streamify.app.R.string.notification_default_body)
        val sectionStr = data["section"]?.uppercase() ?: "INFO"
        val section = runCatching { NoticeSection.valueOf(sectionStr) }
            .getOrDefault(NoticeSection.INFO)
        val priority = data["priority"]?.toIntOrNull() ?: 0
        val expiresAt = data["expiresAt"]?.toLongOrNull()
        val deepLink = data["deepLink"]?.takeIf { it.isNotBlank() }
        val messageId = message.messageId ?: java.util.UUID.randomUUID().toString()
        val id = data["id"] ?: "push:$messageId"

        val attachments = data["attachments"]?.let { raw ->
            runCatching {
                JSONArray(raw).let { arr ->
                    List(arr.length()) { i ->
                        NoticeAttachment.fromJson(arr.optJSONObject(i) ?: JSONObject())
                    }
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()

        val notice = Notice(
            id = id,
            title = title,
            body = body,
            section = section,
            priority = priority,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
            attachments = attachments,
            deepLink = deepLink,
            isPushSourced = true
        )

        // (1) Persist — Observe-and-render via the Fragment.
        //    Phase 5.7 hot-guard: if (rare) the app is mid-initialisation
        //    and `app.repository` hasn't been resolved yet (e.g. a Phase 6
        //    async-init split), bail quietly — we'll get the next push.
        val noticeRepo: NoticeRepository? = (applicationContext as? StreamifyApp)
            ?.repository
            ?.noticeRepository
        if (noticeRepo == null) {
            Log.w("FCMService", "notice insert skipped: app.repository not yet resolved")
            return
        }
        scope.launch {
            runCatching { noticeRepo.insertPushSourced(notice) }
                .onFailure { Log.w("FCMService", "notice insert failed: ${it.message}", it) }
        }

        // (2) OS-level banner in the live_events channel so the alert
        // surfaces even when NoticeFragment isn't on screen.
        val notifId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val deepLinkUri: Uri? = deepLink?.let { runCatching { Uri.parse(it) }.getOrNull() }
        NotificationHelper.showLiveEventNotification(
            context = applicationContext,
            notificationId = notifId,
            title = title,
            body = body,
            deepLinkUri = deepLinkUri
        )
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        // Backend dropped >20 queued messages. Re-sync via
        // `GET /api/notifications` when the admin composer lands (Phase 8).
        Log.w("FCMService", "FCM dropped queued messages; will re-sync after Phase 8")
    }

    /**
     * Best-effort token registration against `/api/fcm/register`. The
     * backend endpoint is implemented in Phase 8 Step 8.x; until then
     * this is a no-op (the request will 404 but we don't surface the
     * failure to the user — the next FCM rotation retries).
     *
     * URL construction uses [okhttp3.HttpUrl.newBuilder] on the parsed
     * base URL. `newBuilder()` carries the existing scheme / host /
     * port / path / query forward, so the `"/api"` prefix in
     * `AppConfig.apiBaseUrl` is **preserved** (verified against the
     * regression noted by the Step 5.4 code reviewer, pass 1: the prior
     * manual scheme/host/port copy dropped the path).
     */
    private suspend fun postTokenToBackend(token: String) {
        val app = applicationContext as StreamifyApp
        val baseUrl = com.streamify.app.data.remote.AppConfig.defaults().apiBaseUrl
        val url = runCatching {
            baseUrl.toHttpUrl()
                .newBuilder()
                // apiBaseUrl typically ends with "/api"; the caller's intent
                // is /api/fcm/register. We add the two segments one-at-a-time
                // via the unambiguous single-string overload — OkHttp 4's
                // `addPathSegments(String...)` resolves ambiguously when
                // called with multiple string literals and the Kotlin
                // compiler may pick an overload expecting a Boolean flag.
                .addPathSegment("fcm")
                .addPathSegment("register")
                .build()
                .toString()
        }.getOrElse {
            Log.w("FCMService", "Token register skipped: invalid apiBaseUrl '$baseUrl'")
            return
        }
        val payload = JSONObject().apply {
            put("token", token)
            put("platform", "android")
            put("appVersion", com.streamify.app.BuildConfig.VERSION_NAME)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        // The application's network.httpClient is already configured
        // with the shared cache + auth interceptor.
        val httpClient: OkHttpClient = app.network.httpClient as OkHttpClient
        val request = Request.Builder().url(url).post(body).build()
        runCatching { httpClient.newCall(request).execute().use { /* fire-and-forget */ } }
            .onFailure { Log.w("FCMService", "Token register POST failed: ${it.message}") }
    }

    /**
     * Phase 5 · Step 5.4 — reaps in-flight coroutines when the OS reaps
     * this Service instance. We cancel all children of the
     * CoroutineScope's Job rather than the scope itself because only
     * the children are ours; awaitAuth-bootstrap / lifecycle coroutines
     * that the FCM SDK might attach later get to run unaffected.
     */
    override fun onDestroy() {
        scope.coroutineContext.cancelChildren()
        super.onDestroy()
    }
}
