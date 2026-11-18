package com.aird.security

import java.net.URI

/** What this app is willing to talk to. Public hosts must be HTTPS. HTTP stays on the local network. */
object HostPolicy {
    fun host(raw: String): String? {
        val uri = parse(raw) ?: return null
        return bareHost(uri.host.orEmpty()).takeIf { it.isNotEmpty() }
    }

    fun isSafeRemoteUrl(raw: String): Boolean {
        val uri = parse(raw) ?: return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = bareHost(uri.host.orEmpty())
        if (host.isEmpty()) return false
        if (scheme == "https") return true
        if (scheme == "http") return allowsCleartext(host)
        return false
    }

    fun allowsCleartext(host: String): Boolean {
        val name = bareHost(host)
        if (name.isEmpty()) return false
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".home.arpa")) {
            return true
        }
        val ipv4 = name.split('.')
        if (ipv4.size == 4 && ipv4.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
            val a = ipv4[0].toInt()
            val b = ipv4[1].toInt()
            if (a == 10 || a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 169 && b == 254) return true
            return false
        }
        if (name.contains(':')) {
            if (name == "::1" || name == "0:0:0:0:0:0:0:1") return true
            if (name.startsWith("fe80:")) return true
            if (name.startsWith("fc") || name.startsWith("fd")) return true
        }
        return false
    }

    fun sameHost(left: String, right: String): Boolean {
        val a = host(left) ?: return false
        val b = host(right) ?: return false
        return a.equals(b, ignoreCase = true)
    }

    private fun bareHost(raw: String): String =
        raw.trim().removePrefix("[").removeSuffix("]").trim().trimEnd('.').lowercase()

    private fun parse(raw: String): URI? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            URI(trimmed)
        } catch (_: Exception) {
            null
        }
    }
}
