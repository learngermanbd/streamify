package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName
import org.json.JSONObject

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Team. Nested inside [Event]; no id field
 * (the slot identity comes from the parent's teamA/teamB position).
 */
data class Team(
    @SerializedName("name")    val name: String,
    @SerializedName("logoUrl") val logoUrl: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        logoUrl?.let { put("logoUrl", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): Team = Team(
            name = json.optString("name", ""),
            logoUrl = json.optString("logoUrl", "").takeIf { it.isNotEmpty() }
        )
    }
}
