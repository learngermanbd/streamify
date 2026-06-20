package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Phase 2 · Step 2.1 \u2014 Event model.
 *
 * Top-level live-event payload returned by the SportStream admin API's
 * `/api/events` endpoint. Composed of nested [Team] and a list of [StreamLink]
 * quality fallbacks (typically HLS, DASH, progressive MP4).
 *
 * Parsed via org.json for now; kotlinx.serialization lands in Phase 8 \u00b7 Step 8.2.
 */
data class Event(
    @SerializedName("id")           val id: String,
    @SerializedName("title")        val title: String,
    @SerializedName("teamA")        val teamA: Team,
    @SerializedName("teamB")        val teamB: Team,
    @SerializedName("date")         val date: String,
    @SerializedName("time")         val time: String,
    @SerializedName("isLive")       val isLive: Boolean = false,
    @SerializedName("category")     val category: String,
    @SerializedName("streams")      val streams: List<StreamLink> = emptyList(),
    @SerializedName("status")       val status: EventStatus = EventStatus.DRAFT,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("teamA", teamA.toJson())
        put("teamB", teamB.toJson())
        put("date", date)
        put("time", time)
        put("isLive", isLive)
        put("category", category)
        put("streams", StreamLink.listToJsonArray(streams))
        put("status", status.name)
        thumbnailUrl?.let { put("thumbnailUrl", it) }
    }

    companion object {
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): Event = Event(
            id = json.optString("id", ""),
            title = json.optString("title", ""),
            teamA = Team.fromJson(json.optJSONObject("teamA") ?: JSONObject()),
            teamB = Team.fromJson(json.optJSONObject("teamB") ?: JSONObject()),
            date = json.optString("date", ""),
            time = json.optString("time", ""),
            isLive = json.optBoolean("isLive", false),
            category = json.optString("category", ""),
            streams = StreamLink.listFromJsonArray(json.optJSONArray("streams") ?: JSONArray()),
            status = runCatching { EventStatus.valueOf(json.optString("status", "DRAFT")) }
                .getOrDefault(EventStatus.DRAFT),
            thumbnailUrl = json.optString("thumbnailUrl", "").takeIf { it.isNotEmpty() }
        )

        /** Mirror of the [StreamLink.listFromJsonArray] helper. */
        fun listFromJsonArray(rawJson: String): List<Event> {
            val arr = JSONArray(rawJson)
            return (0 until arr.length()).map { i ->
                fromJson(arr.optJSONObject(i) ?: JSONObject())
            }
        }
    }
}
