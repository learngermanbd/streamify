package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Playlist. A user-curated list of [StreamLink]s
 * (Phase 5 \u00b7 Step 5.2 wires CRUD UI). `ownerId` is opaque (in v1 it's just the
 * device-local user id; multi-account lands in Phase 8).
 */
data class Playlist(
    @SerializedName("id")        val id: String,
    @SerializedName("name")      val name: String,
    @SerializedName("items")     val items: List<StreamLink>,
    /** Epoch milliseconds when the playlist was first created. */
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("ownerId")   val ownerId: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("items", StreamLink.listToJsonArray(items))
        put("createdAt", createdAt)
        put("ownerId", ownerId)
    }

    companion object {
        fun fromJson(json: JSONObject): Playlist = Playlist(
            id        = json.optString("id", ""),
            name      = json.optString("name", ""),
            items     = StreamLink.listFromJsonArray(json.optJSONArray("items") ?: JSONArray()),
            createdAt = json.optLong("createdAt", 0L),
            ownerId   = json.optString("ownerId", "")
        )

        /** Mirror of the [StreamLink.listFromJsonArray] helper. */
        fun listFromJsonArray(rawJson: String): List<Playlist> {
            val arr = JSONArray(rawJson)
            return (0 until arr.length()).map { i ->
                fromJson(arr.optJSONObject(i) ?: JSONObject())
            }
        }
    }
}
