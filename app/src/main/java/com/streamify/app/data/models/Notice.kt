package com.streamify.app.data.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 5 · Step 5.5 v2 — Notice (multi-section, attachment-bearing).
 *
 * The v1 minimal model was a single free-form string carried in
 * [com.streamify.app.data.remote.AppConfig.noticeText]. v2 promotes
 * the model to a list of structured [Notice]s so we can render rich
 * entries (title + body + section badge + priority + attachments +
 * expiration + deep link) on the NoticeFragment's RecyclerView.
 *
 * @param id            Stable backend OR push-supplied id. Push-sourced
 *                      notices use a synthesized id of the form
 *                      "push:<fcm-message-id>" so a re-push with the
 *                      same upstream id de-duplicates via Room's
 *                      OnConflictStrategy.REPLACE.
 * @param title         Headline. v1 had no title (string was the body).
 * @param body          Markdown-light body. Supports newlines; rendered
 *                      verbatim in the card.
 * @param section       UI grouping; stable order = [ALERT, INFO, PROMO].
 * @param priority      0 = normal (default). Higher pins the row to the
 *                      top of its section. ALERT section items are
 *                      further sorted by priority DESC.
 * @param createdAt     Epoch millis. Room sorts DESC.
 * @param expiresAt     Epoch millis nullable. Items whose expiresAt
 *                      has passed are filtered at the Repo layer (NOT
 *                      removed from Room so re-renders stay cheap).
 * @param attachments  Image / Link / File mix. Each has a [url] +
 *                      [NoticeAttachmentType]. File type is forward-compat
 *                      (we won't download yet).
 * @param deepLink      Optional streamify:// URI for tap-target.
 * @param isPushSourced Default false. Set to true by
 *                      [com.streamify.app.services.StreamifyMessagingService]
 *                      when a `data["type"]=="notice"` payload writes
 *                      the entry — useful for prune-on-expire logic.
 */
data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val section: NoticeSection,
    val priority: Int,
    val createdAt: Long,
    val expiresAt: Long?,
    val attachments: List<NoticeAttachment>,
    val deepLink: String?,
    val isPushSourced: Boolean = false
) {
    companion object {
        /**
         * Defensive JSON parser. Missing optional fields fall back to
         * null / empty list / 0. Section defaults to [NoticeSection.INFO]
         * so a malformed backend payload still renders something
         * useful instead of crashing the screen.
         */
        fun fromJson(json: JSONObject): Notice {
            val sectionStr = json.optString("section", "INFO").uppercase()
            val section = runCatching { NoticeSection.valueOf(sectionStr) }
                .getOrDefault(NoticeSection.INFO)
            val attachmentsArr = json.optJSONArray("attachments")
            val attachments = if (attachmentsArr != null) {
                List(attachmentsArr.length()) { i ->
                    NoticeAttachment.fromJson(attachmentsArr.optJSONObject(i) ?: JSONObject())
                }
            } else {
                emptyList()
            }
            return Notice(
                id            = json.optString("id"),
                title         = json.optString("title"),
                body          = json.optString("body"),
                section       = section,
                priority      = json.optInt("priority", 0),
                createdAt     = json.optLong("createdAt", System.currentTimeMillis()),
                expiresAt     = if (json.isNull("expiresAt")) null else json.optLong("expiresAt", -1L).takeIf { it > 0 },
                attachments   = attachments,
                deepLink      = json.optString("deepLink", "").takeIf { it.isNotBlank() },
                isPushSourced = json.optBoolean("isPushSourced", false)
            )
        }

        fun listFromJsonArray(arr: JSONArray): List<Notice> = List(arr.length()) { i ->
            fromJson(arr.optJSONObject(i) ?: JSONObject())
        }
    }
}

/**
 * UI grouping for notices. Stable section order in the rendered list:
 * ALERT first (urgent), then INFO (general), then PROMO (deals).
 * See [Notice.companion.fromJson] for missing-field fallback.
 */
enum class NoticeSection { ALERT, INFO, PROMO }

/**
 * Per-row attachment type. Drives the icon + tap behavior in
 * [com.streamify.app.ui.adapters.NoticeAdapter.AttachmentViewHolder]:
 *
 *  - IMAGE: tap → open full-screen image preview (Phase 9 polish)
 *  - LINK : tap → start ACTION_VIEW
 *  - FILE : tap → no-op for now (Phase 9 attaches download service)
 */
enum class NoticeAttachmentType { IMAGE, LINK, FILE }

data class NoticeAttachment(
    val url: String,
    val type: NoticeAttachmentType,
    val mimeType: String? = null,
    val label: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): NoticeAttachment {
            val typeStr = json.optString("type", "LINK").uppercase()
            val type = runCatching { NoticeAttachmentType.valueOf(typeStr) }
                .getOrDefault(NoticeAttachmentType.LINK)
            return NoticeAttachment(
                url       = json.optString("url", ""),
                type      = type,
                mimeType  = json.optString("mimeType", "").takeIf { it.isNotBlank() },
                label     = json.optString("label", "").takeIf { it.isNotBlank() }
            )
        }
    }
}
