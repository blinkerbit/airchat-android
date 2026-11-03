package com.aird.chat.data

import android.content.Context
import com.aird.chat.crypto.ChatCrypto
import com.aird.session.AirdHttp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object ChatE2E {
    private val lock = Mutex()
    private var identity: ChatCrypto.Identity? = null
    private var username: String = ""
    private val keys = mutableMapOf<String, ByteArray>()
    @Volatile private var generation = 0

    fun clear() {
        generation += 1
        identity = null
        username = ""
        keys.clear()
    }

    suspend fun clearKey(convId: String) = lock.withLock {
        keys.remove(convId)
    }

    suspend fun prepare(context: Context, user: String) = lock.withLock {
        val gen = generation
        username = user
        if (identity != null) return@withLock
        val response = try {
            AirdHttp.get(context, "/api/chat/e2e/identity")
        } catch (_: Exception) {
            return@withLock
        }
        if (!response.ok || gen != generation) return@withLock
        val root = response.json()
        if (root.has("backup") && !root.isNull("backup")) {
            val loaded = root.optJSONObject("backup")?.let { readIdentity(it) } ?: return@withLock
            if (gen != generation) return@withLock
            identity = loaded
        } else {
            val fresh = ChatCrypto.generateIdentity()
            val bundle = JSONObject()
                .put("v", 1)
                .put("kind", "aird-chat-e2e-identity")
                .put("username", user)
                .put("publicJwk", JSONObject(fresh.publicJson()))
                .put("privateJwk", JSONObject(fresh.privateJson()))
                .put("kid", fresh.kid())
            val saved = AirdHttp.send(context, "/api/chat/e2e/identity", "PUT", JSONObject().put("backup", bundle))
            if (!saved.ok || gen != generation) return@withLock
            identity = fresh
        }
        val current = identity ?: return@withLock
        if (gen != generation) return@withLock
        AirdHttp.send(context, "/api/chat/e2e/keys", "PUT", JSONObject().put("public_jwk", JSONObject(current.publicJson())))
    }

    suspend fun decrypt(context: Context, convId: String, payload: JSONObject?): String? {
        if (payload == null || payload.optInt("v") != 1) return null
        val raw = keyFor(context, convId) ?: return null
        return try {
            ChatCrypto.decrypt(raw, convId, payload.optString("iv"), payload.optString("ct"))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun encrypt(context: Context, convId: String, text: String): JSONObject? {
        val raw = keyFor(context, convId) ?: return null
        val sealed = ChatCrypto.encrypt(raw, convId, text)
        return JSONObject().put("v", 1).put("iv", sealed.iv).put("ct", sealed.ct)
    }

    private suspend fun keyFor(context: Context, convId: String): ByteArray? = lock.withLock {
        keys[convId]?.let { return@withLock it }
        val me = identity ?: return@withLock null
        val response = try {
            AirdHttp.get(context, "/api/chat/conversations/$convId/e2e")
        } catch (err: Exception) {
            if (err is kotlinx.coroutines.CancellationException) throw err
            return@withLock null
        }
        // A failed read must never look like "no key yet". That would mint a
        // random key nobody else can unwrap.
        if (!response.ok) return@withLock null
        val state = response.json()
        val wraps = state.optJSONObject("wraps") ?: JSONObject()
        val pubs = state.optJSONObject("keys") ?: JSONObject()
        val mine = wraps.optJSONObject(username)
        var raw: ByteArray? = null
        if (mine != null) {
            val epk = mine.optJSONObject("epk")
            raw = try {
                ChatCrypto.unwrap(
                    me,
                    epk?.optString("x").orEmpty(),
                    epk?.optString("y").orEmpty(),
                    convId,
                    mine.optString("iv"),
                    mine.optString("ct"),
                )
            } catch (_: Exception) {
                null
            }
        }
        if (raw == null && wraps.length() > 0) return@withLock null
        val minted = raw == null
        if (raw == null) raw = ChatCrypto.randomKey()
        val next = JSONObject()
        var changed = false
        val names = pubs.keys()
        while (names.hasNext()) {
            val name = names.next()
            val pub = pubs.optJSONObject(name) ?: continue
            val x = pub.optString("x")
            val y = pub.optString("y")
            if (x.isEmpty() || y.isEmpty()) continue
            val existing = wraps.optJSONObject(name)
            val kid = ChatCrypto.fingerprint(x, y)
            if (existing != null && existing.optString("kid") == kid && mine != null) {
                next.put(name, existing)
                continue
            }
            val wrap = ChatCrypto.wrap(raw, x, y, convId)
            next.put(
                name,
                JSONObject()
                    .put("v", 1)
                    .put("kid", wrap.kid)
                    .put("epk", JSONObject().put("kty", "EC").put("crv", "P-256").put("x", wrap.epkX).put("y", wrap.epkY))
                    .put("iv", wrap.iv)
                    .put("ct", wrap.ct),
            )
            changed = true
        }
        // A key nobody can unwrap is worse than no key.
        if (minted && next.length() == 0) return@withLock null
        if (changed && next.length() > 0) {
            val saved = try {
                AirdHttp.send(context, "/api/chat/conversations/$convId/e2e", "PUT", JSONObject().put("wraps", next))
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                return@withLock null
            }
            if (!saved.ok) return@withLock null
        }
        keys[convId] = raw
        raw
    }

    fun currentUsername(): String = username

    fun hasIdentity(): Boolean = identity != null

    suspend fun exportBackup(context: Context? = null): String? = lock.withLock {
        if (identity == null && context != null && username.isNotEmpty()) {
            val response = try {
                AirdHttp.get(context, "/api/chat/e2e/identity")
            } catch (_: Exception) {
                null
            }
            if (response?.ok == true) {
                val root = response.json()
                if (root.has("backup") && !root.isNull("backup")) {
                    identity = root.optJSONObject("backup")?.let { readIdentity(it) }
                }
            }
        }
        val me = identity ?: return@withLock null
        val owner = username.ifBlank { return@withLock null }
        val bundle = JSONObject()
            .put("v", 1)
            .put("kind", "aird-chat-e2e-identity")
            .put("username", owner)
            .put("exported_at", java.time.Instant.now().toString())
            .put("publicJwk", JSONObject(me.publicJson()))
            .put("privateJwk", JSONObject(me.privateJson()))
            .put("kid", me.kid())
        bundle.toString(2)
    }

    suspend fun importBackup(context: Context, jsonStr: String): Result<Unit> = lock.withLock {
        try {
            if (jsonStr.length > 65_536) {
                return Result.failure(IllegalArgumentException("Invalid key backup file"))
            }
            val bundle = JSONObject(jsonStr.trim().removePrefix("\uFEFF"))
            if (bundle.optInt("v") != 1 || bundle.optString("kind") != "aird-chat-e2e-identity") {
                return Result.failure(IllegalArgumentException("Invalid key backup file"))
            }
            val pub = bundle.optJSONObject("publicJwk")
            val kid = bundle.optString("kid")
            val loaded = readIdentity(bundle)
            if (pub == null || kid.isEmpty() || loaded == null) {
                return Result.failure(IllegalArgumentException("Backup file is missing key material"))
            }
            if (pub.optString("x") != loaded.x || pub.optString("y") != loaded.y) {
                return Result.failure(IllegalArgumentException("Invalid key backup file"))
            }
            val owner = bundle.optString("username").trim()
            if (owner.isEmpty()) {
                return Result.failure(IllegalArgumentException("Backup file has no username"))
            }
            val me = username
            if (me.isNotEmpty() && owner != me) {
                return Result.failure(IllegalArgumentException("This backup belongs to $owner, not $me"))
            }
            val saved = AirdHttp.send(context, "/api/chat/e2e/identity", "PUT", JSONObject().put("backup", bundle))
            if (!saved.ok) {
                val err = saved.json().optString("error").ifBlank { "Could not import key" }
                return Result.failure(IllegalStateException(err))
            }
            identity = loaded
            username = owner
            keys.clear()
            generation += 1
            AirdHttp.send(context, "/api/chat/e2e/keys", "PUT", JSONObject().put("public_jwk", JSONObject(loaded.publicJson())))
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: org.json.JSONException) {
            Result.failure(IllegalArgumentException("Invalid key backup file"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "Could not import key"))
        }
    }

    private fun readIdentity(backup: JSONObject): ChatCrypto.Identity? {
        val priv = backup.optJSONObject("privateJwk") ?: return null
        val x = priv.optString("x")
        val y = priv.optString("y")
        val d = priv.optString("d")
        if (x.isEmpty() || y.isEmpty() || d.isEmpty()) return null
        return ChatCrypto.Identity(x, y, d)
    }
}
