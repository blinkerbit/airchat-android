package com.aird.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sender: String,
    val body: String,
    val createdAt: String,
    val mine: Boolean,
    val e2e: Boolean,
    val decryptOk: Boolean,
    val replyToId: String,
    val mentions: String,
    val attachments: String,
    val reactions: String,
    val forwarded: Boolean,
    val edited: Boolean,
    val msgType: String,
    val cardKind: String,
    val cardId: String,
    val cardTitle: String,
)

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val body: String,
    val sender: String,
)

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val conversationId: String,
    val hasMore: Boolean,
    val receipts: String,
    val pins: String,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val title: String,
    val peer: String,
    val preview: String,
    val unread: Int,
    val updatedAt: String,
    val kind: String,
    val muted: Boolean,
    val members: String,
    val online: Boolean,
    val request: String,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val localId: String,
    val conversationId: String,
    val body: String,
    val replyToId: String,
    val createdAt: Long,
)

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val conversationId: String,
    val text: String,
    val updatedAt: Long,
)

@Entity(tableName = "starred", indices = [Index(value = ["conversationId"])])
data class StarredEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val sender: String,
    val body: String,
    val createdAt: String,
    val starredAt: Long,
)

@Entity(tableName = "conversation_prefs")
data class ConversationPrefsEntity(
    @PrimaryKey val conversationId: String,
    val pinnedAt: Long = 0,
    val wallpaper: String = "",
    val wallpaperDim: Float = 0f,
    val accent: Long? = null,
)

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val path: String,
    val name: String,
    val dir: Boolean,
    val touchedAt: Long,
)

@Entity(tableName = "backup_items", indices = [Index(value = ["status"])])
data class BackupItemEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val takenAt: Long,
    val video: Boolean,
    val status: Int,
    val attempts: Int,
    val updatedAt: Long,
)

object BackupStatus {
    const val PENDING = 0
    const val UPLOADING = 1
    const val DONE = 2
    const val FAILED = 3
}
