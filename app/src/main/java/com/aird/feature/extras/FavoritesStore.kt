package com.aird.feature.extras

import android.content.Context
import com.aird.session.AirdHttp
import org.json.JSONObject

data class FavoritePath(val path: String, val name: String)

object FavoritesStore {
    suspend fun list(context: Context): List<FavoritePath> {
        val response = try {
            AirdHttp.get(context, "/api/favorites")
        } catch (_: Exception) {
            return emptyList()
        }
        if (!response.ok) return emptyList()
        val arr = response.json().optJSONArray("favorites") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.opt(i)
                val path = when (raw) {
                    is String -> raw.trim().trim('/')
                    is JSONObject -> raw.optString("path").trim().trim('/')
                    else -> ""
                }
                if (path.isEmpty()) continue
                add(FavoritePath(path, path.substringAfterLast('/').ifBlank { path }))
            }
        }
    }

    suspend fun toggle(context: Context, path: String): Boolean? {
        val clean = path.trim().trim('/')
        if (clean.isEmpty()) return null
        val response = try {
            AirdHttp.send(context, "/api/favorites/toggle", "POST", JSONObject().put("path", clean))
        } catch (_: Exception) {
            return null
        }
        if (!response.ok) return null
        return response.json().optBoolean("favorited")
    }
}
