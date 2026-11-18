package com.aird.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        MessageFts::class,
        ThreadEntity::class,
        ConversationEntity::class,
        OutboxEntity::class,
        DraftEntity::class,
        StarredEntity::class,
        ConversationPrefsEntity::class,
        RecentFileEntity::class,
        BackupItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AirdDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun threads(): ThreadDao
    abstract fun conversations(): ConversationDao
    abstract fun outbox(): OutboxDao
    abstract fun drafts(): DraftDao
    abstract fun starred(): StarredDao
    abstract fun conversationPrefs(): ConversationPrefsDao
    abstract fun recentFiles(): RecentFileDao
    abstract fun backup(): BackupDao

    companion object {
        fun fileName(user: String): String = "aird-" + safeName(user) + ".db"

        fun open(context: Context, user: String): AirdDatabase =
            Room.databaseBuilder(context.applicationContext, AirdDatabase::class.java, fileName(user))
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun safeName(value: String): String {
            val cleaned = value.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return cleaned.ifBlank { "user" }.take(80)
        }
    }
}
