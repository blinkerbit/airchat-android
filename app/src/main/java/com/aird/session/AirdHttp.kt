package com.aird.session

import android.webkit.CookieManager
import com.aird.ServerStore
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class AirdResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    fun json(): JSONObject = if (body.trim().startsWith("{")) JSONObject(body) else JSONObject()
}

class AirdHttpException(val code: Int, message: String) : Exception(message)

object AirdHttp {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(AndroidCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val uploadClient: OkHttpClient = client.newBuilder()
        .writeTimeout(30, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun get(context: Context, path: String): AirdResponse = request(context, path, "GET", null)

    suspend fun send(context: Context, path: String, method: String, body: JSONObject): AirdResponse =
        request(context, path, method, body)

    suspend fun postRaw(context: Context, path: String, file: File, headers: Map<String, String>): AirdResponse =
        withContext(Dispatchers.IO) {
            CookieManager.getInstance().flush()
            val builder = Request.Builder()
                .url(absolute(context, path))
                .header("Accept", "*/*")
                .header("Origin", ServerStore.get(context))
            headers.forEach { (key, value) -> builder.header(key, value) }
            val token = xsrf(ServerStore.get(context))
            if (token.isNotEmpty()) builder.header("X-XSRFToken", token)
            builder.post(file.asRequestBody("application/octet-stream".toMediaType()))
            uploadClient.newCall(builder.build()).execute().use { response ->
                AirdResponse(response.code, response.body.string())
            }
        }

    suspend fun upload(context: Context, path: String, body: RequestBody): AirdResponse =
        withContext(Dispatchers.IO) {
            CookieManager.getInstance().flush()
            val url = absolute(context, path)
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Origin", ServerStore.get(context))
                .post(body)
            val token = xsrf(ServerStore.get(context))
            if (token.isNotEmpty()) builder.header("X-XSRFToken", token)
            uploadClient.newCall(builder.build()).execute().use { response ->
                AirdResponse(response.code, response.body.string())
            }
        }

    suspend fun saveTo(
        context: Context,
        path: String,
        dest: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        CookieManager.getInstance().flush()
        dest.parentFile?.mkdirs()
        val url = absolute(context, path)
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")
            .header("Origin", ServerStore.get(context))
        attachWebViewCookies(builder, context, url)
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) error("Could not open the file (${response.code})")
                val body = response.body
                val length = body.contentLength()
                val total = if (length >= 0) length else -1L
                var sent = 0L
                onProgress(sent, total)
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            onProgress(sent, total)
                        }
                    }
                }
                if (!dest.exists() || dest.length() == 0L) error("Empty file")
                response.header("Content-Type").orEmpty().substringBefore(';').trim()
            }
        } catch (err: Exception) {
            dest.delete()
            throw err
        }
    }

    internal fun attachWebViewCookies(builder: Request.Builder, context: Context, url: String) {
        val manager = CookieManager.getInstance()
        val server = ServerStore.get(context)
        val cookie = manager.getCookie(url)?.takeIf { it.isNotBlank() }
            ?: manager.getCookie(server)?.takeIf { it.isNotBlank() }
        if (cookie != null) builder.header("Cookie", cookie)
    }

    private suspend fun request(context: Context, path: String, method: String, body: JSONObject?): AirdResponse =
        withContext(Dispatchers.IO) {
            CookieManager.getInstance().flush()
            val url = absolute(context, path)
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("Origin", ServerStore.get(context))
            val token = xsrf(ServerStore.get(context))
            if (token.isNotEmpty() && method != "GET") builder.header("X-XSRFToken", token)
            if (body == null) builder.method(method, if (method == "GET") null else "{}".toRequestBody(jsonType))
            else builder.method(method, body.toString().toRequestBody(jsonType))
            client.newCall(builder.build()).execute().use { response ->
                AirdResponse(response.code, response.body.string())
            }
        }

    fun absolute(context: Context, path: String): String {
        if (path.startsWith("http")) return path
        return ServerStore.get(context) + path
    }

    fun xsrf(server: String): String {
        val raw = CookieManager.getInstance().getCookie(server) ?: return ""
        val match = Regex("(?:^|; )_xsrf=([^;]*)").find(raw) ?: return ""
        return URLDecoder.decode(match.groupValues[1], "UTF-8").trim().trim('"')
    }
}

private class AndroidCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = CookieManager.getInstance()
        val header = manager.getCookie(url.toString())
            ?: manager.getCookie("${url.scheme}://${url.host}")
            ?: return emptyList()
        return header.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty() || trimmed.contains('=').not()) return@mapNotNull null
            val name = trimmed.substringBefore('=').trim()
            if (name.equals("Path", true) || name.equals("Expires", true) || name.equals("Max-Age", true)) {
                return@mapNotNull null
            }
            Cookie.parse(url, trimmed)
        }
    }
}
