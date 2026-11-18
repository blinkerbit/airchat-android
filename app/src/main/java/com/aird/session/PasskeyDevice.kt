package com.aird.session

import android.content.Context
import com.aird.ServerStore
import com.aird.security.HostPolicy
import org.json.JSONObject

/** Passkey created on this device for one server account. Other devices are ignored. */
object PasskeyDevice {
    private const val PREFS = "aird_passkey_device"
    private const val AUTHENTIK_MARK = "authentik"

    @Volatile
    var skipped: Boolean = false

    fun resetSession() {
        skipped = false
    }

    fun hasLocal(context: Context, username: String): Boolean = read(context, username) != null

    fun markAuthentik(context: Context, username: String) {
        write(context, username, AUTHENTIK_MARK)
    }

    fun markCredential(context: Context, username: String, credentialId: String) {
        val id = credentialId.trim()
        if (id.isEmpty()) return
        write(context, username, id)
    }

    suspend fun shouldOffer(context: Context, username: String): Boolean {
        if (skipped || username.isBlank()) return false
        val mode = resolve(context) ?: return false
        val saved = read(context, username)
        if (mode is Mode.Aird) {
            if (saved == AUTHENTIK_MARK) return false
            if (saved.isNullOrBlank()) return true
            val response = try {
                AirdHttp.send(context, "/api/webauthn/auth/options", "POST", JSONObject().put("username", username))
            } catch (_: Exception) {
                return false
            }
            if (response.code == 404 || response.code == 401 || response.code == 403) return false
            if (!response.ok) {
                clear(context, username)
                return true
            }
            if (serverStillHas(response.body, saved)) return false
            clear(context, username)
            return true
        }
        val setup = mode as? Mode.Authentik ?: return false
        if (saved != null) return false
        val probe = try {
            AirdHttp.get(context, setup.url)
        } catch (_: Exception) {
            return false
        }
        return probe.ok
    }

    suspend fun resolve(context: Context): Mode? {
        val status = try {
            AirdHttp.get(context, "/api/webauthn/status")
        } catch (_: Exception) {
            null
        }
        if (status != null && status.ok && status.json().optBoolean("enabled")) return Mode.Aird
        val server = ServerStore.get(context)
        val security = try {
            AirdHttp.get(context, "/api/account/security")
        } catch (_: Exception) {
            null
        }
        val fromApi = if (security != null && security.ok) security.json().optString("passkey_url") else ""
        val url = join(server, fromApi).ifBlank {
            server.trimEnd('/') + "/if/flow/default-authenticator-webauthn-setup/"
        }
        if (!HostPolicy.isSafeRemoteUrl(url)) return null
        return Mode.Authentik(url)
    }

    private fun serverStillHas(body: String, saved: String): Boolean {
        val allow = try {
            JSONObject(body).optJSONArray("allowCredentials")
        } catch (_: Exception) {
            return true
        } ?: return true
        for (i in 0 until allow.length()) {
            val id = allow.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotEmpty() && sameCredentialId(id, saved)) return true
        }
        return false
    }

    private fun read(context: Context, username: String): String? {
        if (username.isBlank()) return null
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(context, username), null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun write(context: Context, username: String, value: String) {
        if (username.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(context, username), value)
            .apply()
    }

    private fun clear(context: Context, username: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(context, username))
            .apply()
    }

    private fun key(context: Context, username: String): String {
        val host = HostPolicy.host(ServerStore.get(context)).orEmpty().lowercase()
        return host + "\n" + username.trim().lowercase()
    }

    private fun join(server: String, raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        if (value.startsWith("/") && !value.startsWith("//")) return server.trimEnd('/') + value
        return ""
    }

    sealed class Mode {
        data object Aird : Mode()
        data class Authentik(val url: String) : Mode()
    }
}

fun sameCredentialId(left: String, right: String): Boolean {
    fun canon(value: String) = value.trim().replace('+', '-').replace('/', '_').trimEnd('=')
    val a = canon(left)
    val b = canon(right)
    return a.isNotEmpty() && a == b
}
