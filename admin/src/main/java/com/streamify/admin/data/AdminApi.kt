package com.streamify.admin.data

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Phase 8 · Step 8.14 — Full REST API client for the admin backend.
 *
 * Handles all CRUD operations for events, channels, highlights, categories,
 * banners, config, notifications, and admin users.
 */
class AdminApi(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val tokenProvider: () -> String?
) {

    // ---- Result types ----

    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Failure(val message: String) : ApiResult<Nothing>()
    }

    // ---- Auth ----

    suspend fun login(email: String, password: String): ApiResult<LoginResponse> = safeApiCall {
        val raw = postJson("/api/admin/auth/login", JSONObject().apply {
            put("email", email)
            put("password", password)
        })
        val json = JSONObject(raw)
        LoginResponse(
            token = json.optString("accessToken", json.optString("token", "")),
            refreshToken = json.optString("refreshToken", ""),
            user = json.optJSONObject("user")?.let {
                AdminUser(
                    id = it.optString("id", ""),
                    email = it.optString("email", ""),
                    name = it.optString("name", ""),
                    role = it.optString("role", "EDITOR")
                )
            }
        )
    }

    // ---- Events ----

    suspend fun getEvents(status: String? = null): ApiResult<List<EventItem>> = safeApiCall {
        val path = buildString {
            append("/api/events")
            if (!status.isNullOrBlank()) append("?status=$status")
        }
        val raw = getJson(path)
        parseJsonArray(raw, "events") { EventItem.fromJson(it) }
    }

    suspend fun createEvent(data: JSONObject): ApiResult<Boolean> = safeApiCall {
        postJson("/api/events", data)
        true
    }

    suspend fun updateEvent(id: String, data: JSONObject): ApiResult<Boolean> = safeApiCall {
        putJson("/api/events/$id", data)
        true
    }

    suspend fun deleteEvent(id: String): ApiResult<Boolean> = safeApiCall {
        deleteJson("/api/events/$id")
        true
    }

    // ---- Channels ----

    suspend fun getChannels(): ApiResult<List<ChannelItem>> = safeApiCall {
        val raw = getJson("/api/channels")
        parseJsonArray(raw, "channels") { ChannelItem.fromJson(it) }
    }

    suspend fun createChannel(data: JSONObject): ApiResult<Boolean> = safeApiCall {
        postJson("/api/channels", data)
        true
    }

    suspend fun updateChannel(id: String, data: JSONObject): ApiResult<Boolean> = safeApiCall {
        putJson("/api/channels/$id", data)
        true
    }

    suspend fun deleteChannel(id: String): ApiResult<Boolean> = safeApiCall {
        deleteJson("/api/channels/$id")
        true
    }

    // ---- Highlights ----

    suspend fun getHighlights(): ApiResult<List<HighlightItem>> = safeApiCall {
        val raw = getJson("/api/highlights")
        parseJsonArray(raw, "highlights") { HighlightItem.fromJson(it) }
    }

    suspend fun createHighlight(data: JSONObject): ApiResult<Boolean> = safeApiCall {
        postJson("/api/highlights", data)
        true
    }

    suspend fun updateHighlight(id: String, data: JSONObject): ApiResult<Boolean> = safeApiCall {
        putJson("/api/highlights/$id", data)
        true
    }

    suspend fun deleteHighlight(id: String): ApiResult<Boolean> = safeApiCall {
        deleteJson("/api/highlights/$id")
        true
    }

    // ---- Categories ----

    suspend fun getCategories(): ApiResult<List<CategoryItem>> = safeApiCall {
        val raw = getJson("/api/categories?includeHidden=true")
        parseJsonArray(raw, "categories") { CategoryItem.fromJson(it) }
    }

    suspend fun createCategory(data: JSONObject): ApiResult<Boolean> = safeApiCall {
        postJson("/api/categories", data)
        true
    }

    suspend fun updateCategory(id: String, data: JSONObject): ApiResult<Boolean> = safeApiCall {
        putJson("/api/categories/$id", data)
        true
    }

    suspend fun deleteCategory(id: String): ApiResult<Boolean> = safeApiCall {
        deleteJson("/api/categories/$id")
        true
    }

    // ---- Config ----

    suspend fun getConfig(): ApiResult<JSONObject> = safeApiCall {
        JSONObject(getJson("/api/config"))
    }

    suspend fun updateConfig(data: JSONObject): ApiResult<Boolean> = safeApiCall {
        putJson("/api/config", data)
        true
    }

    // ---- Notifications ----

    suspend fun sendNotification(data: JSONObject): ApiResult<Boolean> = safeApiCall {
        postJson("/api/notifications/send", data)
        true
    }

    // ---- Analytics ----

    suspend fun getAnalytics(): ApiResult<JSONObject> = safeApiCall {
        JSONObject(getJson("/api/analytics/overview"))
    }

    // ---- Health ----

    suspend fun health(): String = withContext(Dispatchers.IO) {
        val url = joinUrl("/api/health").newBuilder().build()
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            resp.body?.string().orEmpty()
        }
    }

    // ---- HTTP helpers ----

    private suspend fun getJson(path: String): String = withContext(Dispatchers.IO) {
        val req = buildRequest(path).get().build()
        execute(req)
    }

    private suspend fun postJson(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val req = buildRequest(path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        execute(req)
    }

    private suspend fun putJson(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val req = buildRequest(path)
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        execute(req)
    }

    private suspend fun deleteJson(path: String): String = withContext(Dispatchers.IO) {
        val req = buildRequest(path).delete().build()
        execute(req)
    }

    private fun buildRequest(path: String): Request.Builder {
        val builder = Request.Builder()
            .url(joinUrl(path))
            .header("Accept", "application/json")
        val token = tokenProvider()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun execute(req: Request): String {
        val response = httpClient.newCall(req).execute()
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try {
                    JSONObject(raw).optString("error", "HTTP ${resp.code}")
                } catch (_: Exception) {
                    "HTTP ${resp.code}"
                }
                throw IOException(msg)
            }
            return raw
        }
    }

    private fun joinUrl(path: String): HttpUrl =
        (baseUrl.trimEnd('/') + if (path.startsWith('/')) path else "/$path").toHttpUrl()

    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ApiResult.Failure(e.message ?: e.javaClass.simpleName)
    }

    private fun <T> parseJsonArray(raw: String, key: String, mapper: (JSONObject) -> T): List<T> {
        val arr = JSONObject(raw).optJSONArray(key) ?: JSONArray()
        return (0 until arr.length()).map { mapper(arr.getJSONObject(it)) }
    }
}

// ---- Data classes ----

data class LoginResponse(
    val token: String,
    val refreshToken: String,
    val user: AdminUser?
)

data class AdminUser(
    val id: String,
    val email: String,
    val name: String,
    val role: String
)

data class EventItem(
    val id: String, val title: String, val status: String, val isLive: Boolean,
    val teamAName: String, val teamBName: String, val categoryId: String,
    val categoryName: String, val date: String, val time: String,
    val description: String, val thumbnailUrl: String, val streams: List<StreamItem>
) {
    companion object {
        fun fromJson(j: JSONObject) = EventItem(
            id = j.optString("id", ""),
            title = j.optString("title", ""),
            status = j.optString("status", "DRAFT"),
            isLive = j.optBoolean("isLive", false),
            teamAName = j.optString("teamAName", ""),
            teamBName = j.optString("teamBName", ""),
            categoryId = j.optString("categoryId", ""),
            categoryName = j.optJSONObject("category")?.optString("name", "") ?: "",
            date = j.optString("date", ""),
            time = j.optString("time", ""),
            description = j.optString("description", ""),
            thumbnailUrl = j.optString("thumbnailUrl", ""),
            streams = (0 until (j.optJSONArray("streams")?.length() ?: 0)).map { i ->
                StreamItem.fromJson(j.optJSONArray("streams")!!.getJSONObject(i))
            }
        )
    }
}

data class StreamItem(
    val id: String, val name: String, val url: String, val quality: String
) {
    companion object {
        fun fromJson(j: JSONObject) = StreamItem(
            id = j.optString("id", ""),
            name = j.optString("name", ""),
            url = j.optString("url", ""),
            quality = j.optString("quality", "AUTO")
        )
    }
}

data class ChannelItem(
    val id: String, val name: String, val streamUrl: String, val logoUrl: String,
    val categoryId: String, val categoryName: String, val isActive: Boolean,
    val sortOrder: Int
) {
    companion object {
        fun fromJson(j: JSONObject) = ChannelItem(
            id = j.optString("id", ""),
            name = j.optString("name", ""),
            streamUrl = j.optString("streamUrl", ""),
            logoUrl = j.optString("logoUrl", ""),
            categoryId = j.optString("categoryId", ""),
            categoryName = j.optJSONObject("category")?.optString("name", "") ?: "",
            isActive = j.optBoolean("isActive", true),
            sortOrder = j.optInt("sortOrder", 0)
        )
    }
}

data class HighlightItem(
    val id: String, val title: String, val thumbnailUrl: String,
    val videoUrl: String, val date: Long, val duration: Int, val views: Int
) {
    companion object {
        fun fromJson(j: JSONObject) = HighlightItem(
            id = j.optString("id", ""),
            title = j.optString("title", ""),
            thumbnailUrl = j.optString("thumbnailUrl", ""),
            videoUrl = j.optString("videoUrl", ""),
            date = j.optLong("date", 0),
            duration = j.optInt("duration", 0),
            views = j.optInt("views", 0)
        )
    }
}

data class CategoryItem(
    val id: String, val name: String, val iconUrl: String,
    val isVisible: Boolean, val sortOrder: Int
) {
    companion object {
        fun fromJson(j: JSONObject) = CategoryItem(
            id = j.optString("id", ""),
            name = j.optString("name", ""),
            iconUrl = j.optString("iconUrl", ""),
            isVisible = j.optBoolean("isVisible", true),
            sortOrder = j.optInt("sortOrder", 0)
        )
    }
}
