package com.aird.session

import android.content.Context
import android.webkit.CookieManager
import com.aird.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

object Session {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    suspend fun probe(context: Context): Boolean {
        val response = try {
            AirdHttp.get(context, "/api/chat/conversations")
        } catch (_: Exception) {
            _signedIn.value = false
            return false
        }
        if (!response.ok) {
            _signedIn.value = false
            return false
        }
        val list = response.json().optJSONArray("conversations")
        var name = ""
        if (list != null && list.length() > 0) {
            name = list.optJSONObject(0)?.optString("_me").orEmpty()
        }
        if (name.isEmpty()) name = usernameFromChatPage(context)
        if (name.isEmpty()) {
            _signedIn.value = false
            return false
        }
        _username.value = name
        _signedIn.value = true
        return true
    }

    fun signOut(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _signedIn.value = false
        _username.value = ""
    }

    private suspend fun usernameFromChatPage(context: Context): String {
        val response = try {
            AirdHttp.get(context, "/chat")
        } catch (_: Exception) {
            return ""
        }
        val match = Regex("""window\.__AIRD_CHAT_USER__\s*=\s*("(?:\\.|[^"])*")""").find(response.body) ?: return ""
        return try {
            JSONObject("{\"u\":${match.groupValues[1]}}").getString("u")
        } catch (_: Exception) {
            ""
        }
    }

    fun server(context: Context): String = ServerStore.get(context)
}
