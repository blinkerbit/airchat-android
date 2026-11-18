package com.aird.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun thread(conversationId: String): List<MessageEntity>

    @Upsert
    suspend fun upsert(rows: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteThread(conversationId: String)

    @Query(
        """
        SELECT m.* FROM messages m
        JOIN messages_fts f ON m.rowid = f.rowid
        WHERE messages_fts MATCH :query AND m.msgType != 'sync'
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND attachments != '[]'
        ORDER BY createdAt DESC
        """,
    )
    fun withAttachments(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND (body LIKE '%http://%' OR body LIKE '%https://%')
        ORDER BY createdAt DESC
        """,
    )
    fun withLinks(conversationId: String): Flow<List<MessageEntity>>
}

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads WHERE conversationId = :conversationId")
    suspend fun get(conversationId: String): ThreadEntity?

    @Upsert
    suspend fun upsert(row: ThreadEntity)

    @Query("DELETE FROM threads WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY position ASC")
    suspend fun all(): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun clear()

    @Upsert
    suspend fun upsert(rows: List<ConversationEntity>)

    @Transaction
    suspend fun replace(rows: List<ConversationEntity>) {
        clear()
        upsert(rows)
    }
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE localId = :localId")
    suspend fun count(localId: String): Int

    @Upsert
    suspend fun upsert(row: OutboxEntity)

    @Query("DELETE FROM outbox WHERE localId = :localId")
    suspend fun delete(localId: String)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts")
    suspend fun all(): List<DraftEntity>

    @Upsert
    suspend fun upsert(row: DraftEntity)

    @Query("DELETE FROM drafts WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}

@Dao
interface StarredDao {
    @Query("SELECT * FROM starred ORDER BY starredAt DESC")
    fun all(): Flow<List<StarredEntity>>

    @Query("SELECT * FROM starred ORDER BY starredAt DESC")
    suspend fun list(): List<StarredEntity>

    @Query("SELECT messageId FROM starred")
    fun ids(): Flow<List<String>>

    @Upsert
    suspend fun upsert(row: StarredEntity)

    @Query("DELETE FROM starred WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}

@Dao
interface ConversationPrefsDao {
    @Query("SELECT * FROM conversation_prefs")
    suspend fun all(): List<ConversationPrefsEntity>

    @Query("SELECT * FROM conversation_prefs WHERE conversationId = :conversationId")
    fun observe(conversationId: String): Flow<ConversationPrefsEntity?>

    @Upsert
    suspend fun upsert(row: ConversationPrefsEntity)

    @Query("DELETE FROM conversation_prefs WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY touchedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RecentFileEntity>>

    @Upsert
    suspend fun upsert(row: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE path NOT IN (SELECT path FROM recent_files ORDER BY touchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_items WHERE status IN (0, 3) AND attempts < 5 ORDER BY takenAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<BackupItemEntity>

    @Query("SELECT mediaId FROM backup_items")
    suspend fun knownIds(): List<Long>

    @Upsert
    suspend fun upsert(rows: List<BackupItemEntity>)

    @Query("UPDATE backup_items SET status = :status, attempts = attempts + :attemptDelta, updatedAt = :now WHERE mediaId = :mediaId")
    suspend fun mark(mediaId: Long, status: Int, attemptDelta: Int, now: Long)

    @Query("SELECT COUNT(*) FROM backup_items WHERE status = :status")
    fun countFlow(status: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM backup_items")
    fun totalFlow(): Flow<Int>

    @Query("DELETE FROM backup_items")
    suspend fun clear()
}
