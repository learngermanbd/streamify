package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 3 · Step 3.3 — Banner. Top-of-Home carousel slide.
 *
 * Surfaces under a ViewPager2 auto-scroll on the Home tab; usually
 * a promo image with an optional deep-link target (`linkUrl`).
 * Mirrors the [Highlight] / [Channel] data model patterns: Gson
 * `@SerializedName` + manual `JSONObject` parsing in the companion
 * (we use `org.json` for parsing — Gson annotations are kept so
 * future GSON-driven call sites can round-trip).
 */
data class Banner(
    @SerializedName("id")        val id: String,
    @SerializedName("title")     val title: String,
    @SerializedName("imageUrl")  val imageUrl: String,
    /** Optional deep-link target. Empty == non-clickable banner. */
    @SerializedName("linkUrl")   val linkUrl: String = "",
    /** Sort order — lower numbers come first. */
    @SerializedName("sortOrder") val sortOrder: Int = 0,
    /**
     * `true` if the backend marked the banner active; Step 3.3 treats
     * inactive banners as already-invisible (excluded at fetch time).
     */
    @SerializedName("active")    val active: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("imageUrl", imageUrl)
        put("linkUrl", linkUrl)
        put("sortOrder", sortOrder)
        put("active", active)
    }

    companion object {
        fun fromJson(json: JSONObject): Banner = Banner(
            id        = json.optString("id", ""),
            title     = json.optString("title", ""),
            imageUrl  = json.optString("imageUrl", ""),
            linkUrl   = json.optString("linkUrl", ""),
            sortOrder = json.optInt("sortOrder", 0),
            active    = json.optBoolean("active", true)
        )

        fun listFromJsonArray(rawJson: String): List<Banner> {
            val arr = JSONArray(rawJson)
            return (0 until arr.length()).map { i ->
                fromJson(arr.optJSONObject(i) ?: JSONObject())
            }
        }
    }
}
