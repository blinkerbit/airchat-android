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
        val state = AirdHttp.get(context, "/api/chat/conversations/$convId/e2e").json()
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
        if (changed && next.length() > 0) {
            val saved = AirdHttp.send(context, "/api/chat/conversations/$convId/e2e", "PUT", JSONObject().put("wraps", next))
            if (!saved.ok) return@withLock null
        }
        keys[convId] = raw
        raw
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
