package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Channel. One 24 \u00d77 stream (typically a sports network
 * branding); the user-facing "Categories" tab groups these.
 *
 * `category` matches [Category.id] \u2014 admins tag channels for chip filtering;
 * we don't store the full Category object to keep the list lightweight.
 */
data class Channel(
    @SerializedName("id")        val id: String,
    @SerializedName("name")      val name: String,
    @SerializedName("logoUrl")   val logoUrl: String? = null,
    @SerializedName("streamUrl") val streamUrl: String,
    @SerializedName("category")  val category: String,
    @SerializedName("isActive")  val isActive: Boolean = true,
    @SerializedName("sortOrder") val sortOrder: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        logoUrl?.let { put("logoUrl", it) }
        put("streamUrl", streamUrl)
        put("category", category)
        put("isActive", isActive)
        put("sortOrder", sortOrder)
    }

    companion object {
        fun fromJson(json: JSONObject): Channel = Channel(
            id        = json.optString("id", ""),
            name      = json.optString("name", ""),
            logoUrl   = json.optString("logoUrl", "").takeIf { it.isNotEmpty() },
            streamUrl = json.optString("streamUrl", ""),
            category  = json.optString("category", ""),
            isActive  = json.optBoolean("isActive", true),
            sortOrder = json.optInt("sortOrder", 0)
        )

        fun listToJsonArray(items: List<Channel>): JSONArray = JSONArray().apply {
            items.forEach { put(it.toJson()) }
        }

        fun listFromJsonArray(arr: JSONArray): List<Channel> =
            (0 until arr.length()).map { i -> fromJson(arr.optJSONObject(i) ?: JSONObject()) }

        /** String overload added in Step 2.2 — delegates to the JSONArray version above. */
        fun listFromJsonArray(rawJson: String): List<Channel> =
            listFromJsonArray(JSONArray(rawJson))
    }
}
