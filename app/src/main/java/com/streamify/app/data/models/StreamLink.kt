package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Video quality selection for a given stream source.
 *
 * Sustained at three tiers for the user app; admin panel may surface finer
 * FHD/FHD+/UHD tiers once Phase 8 ships.
 */
enum class VideoQuality {
    @SerializedName("AUTO") AUTO,
    @SerializedName("HD")   HD,
    @SerializedName("SD")   SD
}

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 StreamLink. One concrete playable URL attached to
 * either an [Event] (live match) or a [Playlist] (VOD highlight reel).
 */
data class StreamLink(
    @SerializedName("name")    val name: String,
    @SerializedName("url")     val url: String,
    @SerializedName("quality") val quality: VideoQuality = VideoQuality.AUTO
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        put("quality", quality.name)
    }

    companion object {
        fun fromJson(json: JSONObject): StreamLink = StreamLink(
            name = json.optString("name", ""),
            url = json.optString("url", ""),
            quality = runCatching { VideoQuality.valueOf(json.optString("quality", "AUTO")) }
                .getOrDefault(VideoQuality.AUTO)
        )

        fun listToJsonArray(items: List<StreamLink>): JSONArray = JSONArray().apply {
            items.forEach { put(it.toJson()) }
        }

        fun listFromJsonArray(arr: JSONArray): List<StreamLink> =
            (0 until arr.length()).map { i -> fromJson(arr.optJSONObject(i) ?: JSONObject()) }
    }
}
