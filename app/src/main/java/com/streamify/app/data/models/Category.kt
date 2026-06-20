package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Category. Tag used to group [Channel]s and [Event]s.
 * The Home "Categories" tab renders a ChipGroup of all visible categories.
 */
data class Category(
    @SerializedName("id")        val id: String,
    @SerializedName("name")      val name: String,
    @SerializedName("iconUrl")   val iconUrl: String? = null,
    @SerializedName("sortOrder") val sortOrder: Int = 0,
    @SerializedName("isVisible") val isVisible: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        iconUrl?.let { put("iconUrl", it) }
        put("sortOrder", sortOrder)
        put("isVisible", isVisible)
    }

    companion object {
        fun fromJson(json: JSONObject): Category = Category(
            id        = json.optString("id", ""),
            name      = json.optString("name", ""),
            iconUrl   = json.optString("iconUrl", "").takeIf { it.isNotEmpty() },
            sortOrder = json.optInt("sortOrder", 0),
            isVisible = json.optBoolean("isVisible", true)
        )

        fun listToJsonArray(items: List<Category>): JSONArray = JSONArray().apply {
            items.forEach { put(it.toJson()) }
        }

        fun listFromJsonArray(arr: JSONArray): List<Category> =
            (0 until arr.length()).map { i -> fromJson(arr.optJSONObject(i) ?: JSONObject()) }

        /** String overload added in Step 2.2 — delegates to the JSONArray version above. */
        fun listFromJsonArray(rawJson: String): List<Category> =
            listFromJsonArray(JSONArray(rawJson))
    }
}
