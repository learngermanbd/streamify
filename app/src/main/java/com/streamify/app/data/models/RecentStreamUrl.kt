package com.streamify.app.data.models

import org.json.JSONObject

/**
 * Phase 5 · Step 5.3 — A network-stream URL the user has previously opened
 * via the Network Stream screen. Stored in the player-prefs DataStore as a
 * JSON array, capped to the N most-recent entries.
 *
 * @param url         The full HLS(.m3u8) / DASH(.mpd) / progressive URL.
 * @param formatLabel User choice at the time of save — one of
 *                    "AUTO" / "HLS" / "DASH". Used for display on the Recent
 *                    list and as the in-form hint when the entry is tapped;
 *                    the player itself auto-detects so the label is metadata
 *                    only for now.
 * @param addedAtMs   Persisted timestamp (ms since epoch). Newest entries
 *                    sort to the top of the list (`MAX_RECENT_URLS` cap).
 */
data class RecentStreamUrl(
    val url: String,
    val formatLabel: String,
    val addedAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_URL, url)
        put(KEY_FORMAT, formatLabel)
        put(KEY_ADDED_AT, addedAtMs)
    }

    companion object {
        const val FORMAT_AUTO = "AUTO"
        const val FORMAT_HLS = "HLS"
        const val FORMAT_DASH = "DASH"

        private const val KEY_URL = "url"
        private const val KEY_FORMAT = "format"
        private const val KEY_ADDED_AT = "addedAt"

        fun fromJson(obj: JSONObject): RecentStreamUrl = RecentStreamUrl(
            url = obj.optString(KEY_URL).trim(),
            formatLabel = obj.optString(KEY_FORMAT, FORMAT_AUTO),
            addedAtMs = obj.optLong(KEY_ADDED_AT, 0L)
        )
    }
}
