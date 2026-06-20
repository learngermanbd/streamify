package com.streamify.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamify.app.data.models.Notice
import com.streamify.app.data.models.NoticeAttachment
import com.streamify.app.data.models.NoticeSection
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Phase 5 · Step 5.5 v2 — Notice table row.
 *
 * Section is stored as the enum name (string) instead of an ordinal
 * so future enum reorders don't corrupt historical rows. Same
 * rationale drives [priority] = Int (we don't care about bit pattern
 * stability since ordering is by createdAt DESC + priority DESC).
 *
 * `attachmentsJson` is the Gson-encoded [List]<[NoticeAttachment]> —
 * we already established the [Converters.stringListToJson] pattern
 * for [PlaylistEntity.items]; we reuse the same Gson instance at the
 * mapper layer WITHOUT needing a new Converter because the JSON is
 * private to this entity and never round-trips with another table.
 *
 * Index on (section, createdAt) supports the runtime ORDER BY of
 * [NoticeDao.observeAll]; (isPushSourced, createdAt) supports the
 * prune-push-sourced-after-N-days housekeeping query.
 */
@Entity(
    tableName = "notices",
    indices = [
        Index("section", "createdAt"),
        Index("isPushSourced", "createdAt")
    ]
)
data class NoticeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val section: String,
    val priority: Int,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "expiresAt") val expiresAt: Long?,
    @ColumnInfo(name = "attachmentsJson") val attachmentsJson: String,
    @ColumnInfo(name = "deepLink") val deepLink: String?,
    @ColumnInfo(name = "isPushSourced") val isPushSourced: Boolean,
) {
    /**
     * Map back to the domain model. Enum parse failures default to
     * [NoticeSection.INFO] so a bad row never crashes the adapter.
     */
    fun toDomain(): Notice = Notice(
        id            = id,
        title         = title,
        body          = body,
        section       = runCatching { NoticeSection.valueOf(section) }.getOrDefault(NoticeSection.INFO),
        priority      = priority,
        createdAt     = createdAt,
        expiresAt     = expiresAt,
        attachments   = decodeAttachments(attachmentsJson),
        deepLink      = deepLink,
        isPushSourced = isPushSourced,
    )

    companion object {
        private val gson: Gson = Gson()
        private val listType = object : TypeToken<List<NoticeAttachment>>() {}.type

        fun fromDomain(notice: Notice): NoticeEntity = NoticeEntity(
            id              = notice.id,
            title           = notice.title,
            body            = notice.body,
            section         = notice.section.name,
            priority        = notice.priority,
            createdAt       = notice.createdAt,
            expiresAt       = notice.expiresAt,
            attachmentsJson = encodeAttachments(notice.attachments),
            deepLink        = notice.deepLink,
            isPushSourced   = notice.isPushSourced,
        )

        fun encodeAttachments(items: List<NoticeAttachment>): String {
            if (items.isEmpty()) return "[]"
            return gson.toJson(items, listType)
        }

        fun decodeAttachments(json: String): List<NoticeAttachment> {
            if (json.isBlank() || json == "[]") return emptyList()
            return runCatching { gson.fromJson<List<NoticeAttachment>>(json, listType) }
                .getOrNull()
                ?: emptyList()
        }
    }
}
