package com.aird.data.db

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** The signed-in user's database. One file per user so accounts never mix. */
object LocalStore {
    private val lock = Mutex()
    @Volatile private var current: Pair<String, AirdDatabase>? = null

    @Volatile private var retired = ""

    /** Called on sign-in; lets a user whose data was wiped write again. */
    fun revive(user: String) {
        if (retired == user) retired = ""
    }

    suspend fun db(context: Context, user: String): AirdDatabase? {
        if (user.isBlank() || user == retired) return null
        current?.let { (owner, db) -> if (owner == user) return db }
        return lock.withLock {
            if (user == retired) return@withLock null
            current?.let { (owner, db) -> if (owner == user) return@withLock db }
            current?.second?.close()
            val app = context.applicationContext
            val db = AirdDatabase.open(app, user)
            importLegacy(app, user, db)
            current = user to db
            db
        }
    }

    fun peek(user: String): AirdDatabase? = current?.takeIf { it.first == user }?.second

    fun retire(user: String) {
        if (user.isNotBlank()) retired = user
    }

    suspend fun wipe(context: Context, user: String) {
        retire(user)
        lock.withLock {
            val open = current
            if (open != null && (user.isBlank() || open.first == user)) {
                open.second.close()
                current = null
            }
            if (user.isNotBlank()) context.applicationContext.deleteDatabase(AirdDatabase.fileName(user))
            legacyThreadDir(context, user).deleteRecursively()
            File(context.filesDir, LEGACY_OUTBOX).delete()
            File(context.filesDir, "$LEGACY_OUTBOX.part").delete()
        }
    }

    private const val LEGACY_OUTBOX = "chat-outbox.json"

    private fun legacyThreadDir(context: Context, user: String): File {
        val cleaned = user.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "chat" }.take(80)
        return File(context.noBackupFilesDir, "chat-threads/$cleaned")
    }

    /** JSON files from 1.x. Deleted only once their rows are in the database. */
    private suspend fun importLegacy(context: Context, user: String, db: AirdDatabase) {
        val dir = legacyThreadDir(context, user)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        for (file in files) {
            try {
                val root = JSONObject(file.readText())
                val array = root.optJSONArray("messages") ?: continue
                val rows = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val row = legacyMessageOf(item)
                        if (row.id.isNotEmpty() && row.msgType != "sync" && !row.id.startsWith("local-")) add(row)
                    }
                }
                val convId = rows.firstOrNull()?.conversationId.orEmpty()
                if (convId.isNotEmpty()) {
                    db.withTransaction {
                        rows.chunked(400).forEach { chunk -> db.messages().upsert(chunk.map { it.toEntity() }) }
                        db.threads().upsert(
                            ThreadEntity(
                                conversationId = convId,
                                hasMore = root.optBoolean("hasMore", true),
                                receipts = receiptsJson(receiptsOf(root.optJSONArray("receipts"))),
                                pins = pinsJson(pinsOf(root.optJSONArray("pins"))),
                            ),
                        )
                    }
                }
                file.delete()
            } catch (_: Exception) {
            }
        }
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
        val outbox = File(context.filesDir, LEGACY_OUTBOX)
        if (outbox.isFile) {
            try {
                val array = JSONObject(outbox.readText()).optJSONArray("items")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val localId = obj.optString("localId")
                        val conv = obj.optString("conversationId")
                        if (localId.isEmpty() || conv.isEmpty()) continue
                        db.outbox().upsert(
                            OutboxEntity(localId, conv, obj.optString("body"), obj.optString("replyToId"), System.currentTimeMillis() + i),
                        )
                    }
                }
                outbox.delete()
            } catch (_: Exception) {
            }
        }
    }
}

/** Runs database writes one at a time, in the order they were asked for. */
internal class SerialWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in queue) {
                try {
                    job()
                } catch (err: Exception) {
                    if (err is kotlinx.coroutines.CancellationException) throw err
                }
            }
        }
    }

    fun post(job: suspend () -> Unit) {
        queue.trySend(job)
    }

    suspend fun await(job: suspend () -> Unit) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        queue.trySend {
            try {
                job()
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }
}
