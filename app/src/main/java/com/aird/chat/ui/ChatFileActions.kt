package com.aird.chat.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aird.chat.model.ChatAttachment
import com.aird.ServerStore
import com.aird.session.AirdHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

data class AttachmentProgress(val bytes: Long, val total: Long)

/** Download progress keyed by messageId-index. Updated on the IO thread; Compose only collects. */
object ChatDownloads {
    private val gate = Any()
    private val _progress = MutableStateFlow<Map<String, AttachmentProgress>>(emptyMap())
    val progress: StateFlow<Map<String, AttachmentProgress>> = _progress

    fun key(messageId: String, index: Int): String = "$messageId-$index"

    fun publish(key: String, bytes: Long, total: Long) {
        synchronized(gate) {
            _progress.value = _progress.value + (key to AttachmentProgress(bytes, total))
        }
    }

    fun clear(key: String) {
        synchronized(gate) {
            if (key !in _progress.value) return
            _progress.value = _progress.value - key
        }
    }
}

/** Chat file API path; share links are for browser open only. */
fun ChatAttachment.downloadPath(): String = url

fun openHttpUrl(context: Context, raw: String) {
    val trimmed = raw.trim().trimEnd('.', ',', ';', ')')
    if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
    }
}

private val flights = ConcurrentHashMap<String, CompletableDeferred<File?>>()

private suspend fun singleFlight(key: String, block: suspend () -> File?): File? {
    val created = CompletableDeferred<File?>()
    val existing = flights.putIfAbsent(key, created)
    if (existing != null) return existing.await()
    try {
        val value = block()
        created.complete(value)
        return value
    } catch (t: Throwable) {
        created.completeExceptionally(t)
        throw t
    } finally {
        flights.remove(key, created)
    }
}

suspend fun downloadChatAttachment(context: Context, messageId: String, attachment: ChatAttachment): Boolean =
    withContext(Dispatchers.IO) {
        val file = ensureAppFile(context, messageId, attachment) ?: return@withContext false
        if (!file.exists() || file.length() == 0L) return@withContext false
        toast(context, "Saved in app")
        true
    }

suspend fun saveChatAttachmentToDevice(context: Context, messageId: String, attachment: ChatAttachment): Boolean =
    withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val file = ensureAppFile(app, messageId, attachment) ?: return@withContext false
        val name = safeChatFileName(attachment.name)
        val guessedMime = guessChatMime(file.name, attachment.mediaKind)
        val mime = if (guessedMime != "application/octet-stream") guessedMime else "application/octet-stream"
        val saved = exportToDownloads(app, file, name, mime)
        if (saved) toast(app, "Saved to Downloads")
        else toast(app, "Download failed: Could not save to Downloads")
        saved
    }

suspend fun openChatAttachment(context: Context, messageId: String, attachment: ChatAttachment): Boolean =
    withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val dest = ensureAppFile(app, messageId, attachment) ?: return@withContext false
            val name = safeChatFileName(attachment.name)
            val guessedMime = guessChatMime(dest.name, attachment.mediaKind)
            val mime = if (guessedMime != "application/octet-stream") guessedMime else "application/octet-stream"
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dest)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                try {
                    app.startActivity(Intent.createChooser(view, "Open $name").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {
                    Toast.makeText(app, "No application found to open $name", Toast.LENGTH_LONG).show()
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            toast(app, "Could not open file: ${e.localizedMessage}")
            false
        }
    }

fun openAirdShareInBrowser(context: Context, shareUrl: String) {
    val path = shareUrl.trim()
    if (path.isEmpty()) return
    val absolute = if (path.startsWith("http")) path else ServerStore.get(context) + path
    openHttpUrl(context, absolute)
}

private suspend fun ensureAppFile(context: Context, messageId: String, attachment: ChatAttachment): File? {
    val app = context.applicationContext
    val path = attachment.downloadPath()
    if (path.isBlank() || messageId.isBlank()) return null
    val dest = appChatFile(app, messageId, attachment.index, attachment.name)
    if (dest.isFile && dest.length() > 0L) return dest
    val key = ChatDownloads.key(messageId, attachment.index)
    return singleFlight(key) {
        if (dest.isFile && dest.length() > 0L) return@singleFlight dest
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            part.delete()
            AirdHttp.saveTo(app, path, part) { bytes, total ->
                ChatDownloads.publish(key, bytes, total)
            }
            if (looksLikeErrorBody(part)) {
                part.delete()
                toast(app, "File is not available on server")
                return@singleFlight null
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            if (!dest.isFile || dest.length() == 0L) {
                dest.delete()
                part.delete()
                toast(app, "Download failed: Empty file")
                return@singleFlight null
            }
            dest
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            if (dest.exists() && dest.length() == 0L) dest.delete()
            e.printStackTrace()
            toast(app, "Download failed: ${e.localizedMessage}")
            null
        } finally {
            ChatDownloads.clear(key)
        }
    }
}

private fun appChatFile(context: Context, messageId: String, index: Int, name: String): File {
    val dir = File(context.filesDir, "chat-files").apply { mkdirs() }
    val id = messageId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "msg" }
    return File(dir, "$id-$index-${safeChatFileName(name)}")
}

private fun looksLikeErrorBody(file: File): Boolean {
    if (!file.exists() || file.length() == 0L) return true
    if (file.length() > 4096) return false
    val head = file.inputStream().use { input ->
        val buf = ByteArray(file.length().toInt())
        val read = input.read(buf)
        if (read <= 0) byteArrayOf() else buf.copyOf(read)
    }
    val text = head.toString(Charsets.UTF_8).trim()
    return text.startsWith("{") && text.contains("\"error\"") && text.endsWith("}")
}

private fun exportToDownloads(context: Context, file: File, displayName: String, mime: String): Boolean {
    return try {
        writePublicDownload(context, file, displayName, mime)
        true
    } catch (_: Exception) {
        try {
            @Suppress("DEPRECATION")
            val fallbackDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            fallbackDir.mkdirs()
            file.copyTo(File(fallbackDir, displayName), overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun writePublicDownload(context: Context, file: File, displayName: String, mime: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            } ?: error("stream failed")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (err: Exception) {
            resolver.delete(uri, null, null)
            throw err
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        file.copyTo(File(dir, displayName), overwrite = true)
    }
}

private suspend fun toast(context: Context, text: String) {
    withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}

fun safeChatFileName(name: String): String {
    val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it in "._- " }
        .trim()
        .take(120)
    return cleaned.ifBlank { "file" }
}

fun guessChatMime(name: String, kind: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "apk" || name.endsWith(".apk", true)) return "application/vnd.android.package-archive"
    if (ext == "pdf") return "application/pdf"
    if (ext == "zip") return "application/zip"
    if (ext == "txt") return "text/plain"
    if (kind == "gif" || name.endsWith(".gif", true)) return "image/gif"
    if (kind == "image") return "image/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        .orEmpty()
        .ifBlank { "application/octet-stream" }
}
