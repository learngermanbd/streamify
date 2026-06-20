package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Highlight. A short-form VOD clip surfaced in the
 * "Highlights" tab. UI formats `date` (epoch ms) and `duration` (seconds) at
 * presentation time.
 */
data class Highlight(
    @SerializedName("id")           val id: String,
    @SerializedName("title")        val title: String,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String,
    @SerializedName("videoUrl")     val videoUrl: String,
    /** Epoch milliseconds; UI formats via java.text.DateFormat. */
    @SerializedName("date")         val date: Long,
    /** Duration in seconds; UI formats as `mm:ss`. */
    @SerializedName("duration")     val duration: Int = 0,
    @SerializedName("views")        val views: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("thumbnailUrl", thumbnailUrl)
        put("videoUrl", videoUrl)
        put("date", date)
        put("duration", duration)
        put("views", views)
    }

    companion object {
        fun fromJson(json: JSONObject): Highlight = Highlight(
            id           = json.optString("id", ""),
            title        = json.optString("title", ""),
            thumbnailUrl = json.optString("thumbnailUrl", ""),
            videoUrl     = json.optString("videoUrl", ""),
            date         = json.optLong("date", 0L),
            duration     = json.optInt("duration", 0),
            views        = json.optInt("views", 0)
        )

        /** Mirror of the [Channel.listFromJsonArray] helper. */
        fun listFromJsonArray(rawJson: String): List<Highlight> {
            val arr = JSONArray(rawJson)
            return (0 until arr.length()).map { i ->
                fromJson(arr.optJSONObject(i) ?: JSONObject())
            }
        }
    }
}
