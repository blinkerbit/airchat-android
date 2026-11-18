package com.aird.session

import android.content.Context
import android.webkit.CookieManager
import com.aird.AirdConnectionService
import com.aird.ServerStore
import com.aird.chat.data.ChatE2E
import com.aird.chat.data.ChatNotifier
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.UnreadBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

object Session {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    private const val PREFS = "aird_session"

    /**
     * Signs in from the last confirmed session so the app opens instantly and works offline.
     * [probe] confirms it with the server and signs out if the cookie is no longer valid.
     */
    fun restore(context: Context): Boolean {
        if (_signedIn.value) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val user = prefs.getString("user", null).orEmpty()
        val server = prefs.getString("server", null).orEmpty()
        if (user.isEmpty() || server != ServerStore.get(context)) return false
        val cookie = try {
            CookieManager.getInstance().getCookie(server)
        } catch (_: Exception) {
            null
        }
        if (cookie.isNullOrBlank()) return false
        _username.value = user
        _signedIn.value = true
        return true
    }

    suspend fun probe(context: Context): Boolean {
        val response = try {
            AirdHttp.get(context, "/api/chat/conversations")
        } catch (_: Exception) {
            return false
        }
        if (!response.ok) {
            if (response.code == 401 || response.code == 403) signOut(context)
            return false
        }
        val list = response.json().optJSONArray("conversations")
        var name = ""
        if (list != null && list.length() > 0) {
            name = list.optJSONObject(0)?.optString("_me").orEmpty()
        }
        if (name.isEmpty()) name = usernameFromChatPage(context) ?: return false
        if (name.isEmpty()) {
            if (_signedIn.value) signOut(context) else _signedIn.value = false
            return false
        }
        if (_username.value.isNotEmpty() && !_username.value.equals(name, ignoreCase = false)) {
            ChatRepository.clear()
        }
        _username.value = name
        _signedIn.value = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("user", name)
            .putString("server", ServerStore.get(context))
            .apply()
        return true
    }

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        UnreadBadge.apply(context, 0)
        ChatNotifier.forget(context)
        com.aird.AppLock.onSignedOut()
        ChatRepository.clear()
        ChatE2E.clear()
        PasskeyDevice.resetSession()
        AirdSocket.stop()
        AirdConnectionService.stop(context)
        _signedIn.value = false
        _username.value = ""
    }

    /** Null when the server could not be reached; empty when it answered without a user. */
    private suspend fun usernameFromChatPage(context: Context): String? {
        val response = try {
            AirdHttp.get(context, "/chat")
        } catch (_: Exception) {
            return null
        }
        if (response.code == 0 || response.code >= 500) return null
        val match = Regex("""window\.__AIRD_CHAT_USER__\s*=\s*("(?:\\.|[^"])*")""").find(response.body) ?: return ""
        return try {
            JSONObject("{\"u\":${match.groupValues[1]}}").getString("u")
        } catch (_: Exception) {
            ""
        }
    }

    fun server(context: Context): String = ServerStore.get(context)
}
