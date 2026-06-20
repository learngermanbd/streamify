package com.streamify.app.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.streamify.app.data.models.StreamLink

/**
 * Phase 2 \u00b7 Step 2.3 \u2014 Room TypeConverters.
 *
 * Wraps the two List-shaped fields we store in Room tables:
 *  \u2022 [StreamLink] list (PlaylistEntity.items \u2014 mirrors the Step 2.1 model)
 *  \u2022 String list (kept for future fields, e.g. Category.tagIds)
 *
 * Empty strings round-trip to `emptyList()` (NOT `[""]`) so downstream
 * callers can treat absent as "no value".
 */
class Converters {

    @TypeConverter
    fun streamLinkListToJson(items: List<StreamLink>): String =
        gson.toJson(items)

    @TypeConverter
    fun jsonToStreamLinkList(json: String?): List<StreamLink> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = gson.fromJson(json, Array<StreamLink>::class.java) ?: return emptyList()
        return arr.toList()
    }

    @TypeConverter
    fun stringListToJson(items: List<String>): String =
        gson.toJson(items)

    @TypeConverter
    fun jsonToStringList(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = gson.fromJson(json, Array<String>::class.java) ?: return emptyList()
        return arr.toList()
    }

    private companion object {
        // Reused across all conversions; thread-safe.
        val gson: Gson = Gson()
    }
}
