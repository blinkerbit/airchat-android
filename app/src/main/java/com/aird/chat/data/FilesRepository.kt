package com.aird.chat.data

import android.content.Context
import android.net.Uri
import com.aird.core.transfer.RangeUpload
import com.aird.session.AirdHttp
import com.aird.session.AirdResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Aird files, folders, tags and uploads. */
object FilesRepository {
    private const val DIRECT_UPLOAD_LIMIT = 32L * 1024 * 1024

    private fun headerEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    suspend fun listFiles(context: Context, path: String): List<AirdEntry>? {
        val clean = path.trim().trim('/')
        val encoded = if (clean.isEmpty()) {
            ""
        } else {
            clean.split('/').joinToString("/") { headerEncode(it) }
        }
        val response = try {
            AirdHttp.get(context, "/api/files/$encoded")
        } catch (_: Exception) {
            return null
        }
        if (!response.ok) return null
        val catalog = tagCatalog(context)
        val files = response.json().optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isEmpty()) continue
                val child = if (clean.isEmpty()) name else "$clean/$name"
                var entry = item.toEntry(name, child)
                val catalogTags = catalog.tags.filter { t ->
                    t.files.any { f -> f.trim().trim('/') == child }
                }.map { it.name }
                if (catalogTags.isNotEmpty()) {
                    val merged = (entry.tags + catalogTags).distinct()
                    entry = entry.copy(tags = merged)
                }
                add(entry)
            }
        }
    }

    suspend fun filesForTag(context: Context, tag: String): List<AirdEntry>? {
        val match = tagCatalog(context).tags.find { it.name == tag } ?: return emptyList()
        return match.files.map { raw ->
            val path = raw.trim().trim('/')
            val name = path.substringAfterLast('/').ifBlank { path }
            AirdEntry(name, path, dir = false, tags = listOf(tag))
        }
    }

    suspend fun tagCatalog(context: Context): TagCatalog {
        val response = try {
            AirdHttp.get(context, "/api/user-tags/browse-catalog")
        } catch (_: Exception) {
            return TagCatalog(emptyList(), emptyMap())
        }
        if (!response.ok) return TagCatalog(emptyList(), emptyMap())
        val body = response.json()
        val tags = buildList {
            val arr = body.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val id = item.optInt("id")
                if (name.isEmpty() || id == 0) continue
                val files = buildList {
                    val paths = item.optJSONArray("files") ?: JSONArray()
                    for (p in 0 until paths.length()) {
                        val path = paths.optString(p).trim().trim('/')
                        if (path.isNotEmpty()) add(path)
                    }
                }
                add(UserTag(id, name, files))
            }
        }
        val names = tags.map { it.name }.ifEmpty {
            buildList {
                val arr = body.optJSONArray("names") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i)
                    if (name.isNotEmpty()) add(name)
                }
            }
        }
        val colors = buildMap {
            val obj = body.optJSONObject("colors") ?: return@buildMap
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, obj.optString(key))
            }
        }
        return TagCatalog(names, colors, tags)
    }

    suspend fun createFolder(context: Context, parent: String, name: String): FileOp =
        formOp(context, "/mkdir", mapOf("parent" to parent.trim().trim('/'), "name" to name.trim()), "Could not create the folder")

    suspend fun deletePath(context: Context, path: String, folder: Boolean): FileOp =
        formOp(
            context,
            "/delete",
            mapOf("path" to path, "recursive" to if (folder) "1" else "0"),
            "Could not delete",
        )

    suspend fun renamePath(context: Context, path: String, newName: String): FileOp =
        formOp(context, "/rename", mapOf("path" to path, "new_name" to newName.trim()), "Could not rename")

    suspend fun transferPath(context: Context, path: String, dest: String, move: Boolean): FileOp =
        formOp(
            context,
            if (move) "/move" else "/copy",
            mapOf("path" to path, "dest" to dest),
            if (move) "Could not move" else "Could not copy",
        )

    suspend fun applyTag(context: Context, path: String, tag: String): FileOp {
        val rel = path.trim().trim('/')
        val name = tag.trim()
        if (rel.isEmpty() || name.isEmpty()) return FileOp(false, "Could not tag")
        val id = userTagId(context, name) ?: return FileOp(false, "Could not resolve tag")
        val response = try {
            AirdHttp.send(
                context,
                "/api/user-tags/$id/links",
                "POST",
                JSONObject().put("kind", "file").put("resource_key", rel),
            )
        } catch (err: Exception) {
            return FileOp(false, err.message ?: "Could not tag")
        }
        if (response.ok) return FileOp(true, "")
        val altResponse = try {
            AirdHttp.send(
                context,
                "/api/user-tags/$id/links",
                "POST",
                JSONObject().put("kind", "file").put("resource_key", "/$rel"),
            )
        } catch (_: Exception) {
            response
        }
        if (altResponse.ok) return FileOp(true, "")
        return FileOp(false, fileMessage(response, "Could not tag"), response.code == 403)
    }

    suspend fun untagPath(context: Context, path: String, tag: String): FileOp {
        val rel = path.trim().trim('/')
        val id = tagCatalog(context).tags.find { it.name.equals(tag, ignoreCase = true) }?.id
            ?: return FileOp(false, "Could not remove the tag")
        val response = try {
            AirdHttp.send(
                context,
                "/api/user-tags/$id/links",
                "DELETE",
                JSONObject().put("kind", "file").put("resource_key", rel),
            )
        } catch (err: Exception) {
            return FileOp(false, err.message ?: "Could not remove the tag")
        }
        if (response.ok) return FileOp(true, "")
        return FileOp(false, fileMessage(response, "Could not remove the tag"), response.code == 403)
    }

    private suspend fun userTagId(context: Context, name: String): Int? {
        val cat = tagCatalog(context)
        cat.tags.find { it.name.equals(name, ignoreCase = true) }?.id?.let { return it }
        val created = try {
            AirdHttp.send(
                context,
                "/api/user-tags",
                "POST",
                JSONObject().put("name", name),
            )
        } catch (_: Exception) {
            null
        }
        if (created != null && created.ok) {
            val id = created.json().optInt("id")
            if (id != 0) return id
        }
        return tagCatalog(context).tags.find { it.name.equals(name, ignoreCase = true) }?.id
    }

    suspend fun uploadUri(
        context: Context,
        dir: String,
        name: String,
        uri: Uri,
        size: Long,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): FileOp = withContext(Dispatchers.IO) {
        val cleanDir = dir.trim().trim('/')
        val cleanName = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "file" }.take(255)
        if (cancelled()) return@withContext FileOp(false, "cancelled")
        if (size >= DIRECT_UPLOAD_LIMIT) {
            val ranged = uploadRanged(context, cleanDir, cleanName, uri, size, cancelled, onProgress)
            if (ranged != null) return@withContext ranged
        }
        val direct = uploadDirect(context, cleanDir, cleanName, uri, size, cancelled, onProgress)
        if (direct.ok || size < 0 || !direct.message.contains("ranged", true)) return@withContext direct
        return@withContext uploadRanged(context, cleanDir, cleanName, uri, size, cancelled, onProgress) ?: direct
    }

    private suspend fun uploadDirect(
        context: Context,
        dir: String,
        name: String,
        uri: Uri,
        size: Long,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): FileOp {
        val length = if (size >= 0) size else -1L
        val response = try {
            AirdHttp.postStream(
                context,
                "/upload",
                length,
                mapOf(
                    "X-Upload-Dir" to headerEncode(dir),
                    "X-Upload-Filename" to headerEncode(name),
                ),
                { context.contentResolver.openInputStream(uri) ?: error("Could not read the file") },
                { sent -> onProgress(sent, length) },
                cancelled,
            )
        } catch (err: Exception) {
            if (cancelled() || err.message?.contains("cancelled") == true) return FileOp(false, "cancelled")
            return FileOp(false, err.message ?: "Could not upload")
        }
        if (response.code == 0 || cancelled()) return FileOp(false, "cancelled")
        if (response.ok) {
            onProgress(length.coerceAtLeast(0), length.coerceAtLeast(0))
            return FileOp(true, "")
        }
        return FileOp(false, fileMessage(response, "Could not upload"), response.code == 403)
    }

    private suspend fun uploadRanged(
        context: Context,
        dir: String,
        name: String,
        uri: Uri,
        size: Long,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): FileOp? {
        if (size <= 0) return null
        val session = try {
            AirdHttp.send(
                context,
                "/api/upload/range/session",
                "POST",
                JSONObject().put("upload_dir", dir).put("filename", name).put("total_size", size),
            )
        } catch (err: Exception) {
            if (cancelled()) return FileOp(false, "cancelled")
            return FileOp(false, err.message ?: "Could not upload")
        }
        if (session.code == 400 && session.body.contains("threshold")) return null
        if (!session.ok) return FileOp(false, fileMessage(session, "Could not upload"), session.code == 403)
        val body = session.json()
        val uploadId = body.optString("upload_id")
        if (uploadId.isEmpty()) return FileOp(false, "Could not upload")
        val chunk = body.optLong("chunk_bytes", RangeUpload.CHUNK_BYTES).coerceIn(256L * 1024, RangeUpload.CHUNK_BYTES)
        val done = coveredRanges(context, uploadId)
        val sent = java.util.concurrent.atomic.AtomicLong(coveredBytes(done, size))
        onProgress(sent.get(), size)
        val pending = RangeUpload.chunks(size, chunk).filter { slice ->
            !rangeCovered(done, slice.start, slice.endInclusive)
        }
        if (cancelled()) return FileOp(false, "cancelled")
        val failure = java.util.concurrent.atomic.AtomicReference<FileOp?>(null)
        val gate = Semaphore(RangeUpload.parallelism(chunk))
        coroutineScope {
            pending.map { slice ->
                async {
                    if (failure.get() != null || cancelled()) return@async
                    gate.withPermit {
                        if (failure.get() != null || cancelled()) return@withPermit
                        val bytes = readSlice(context, uri, slice.start, slice.length.toInt())
                        if (bytes.size.toLong() != slice.length) {
                            failure.compareAndSet(null, FileOp(false, "Could not read the file"))
                            return@withPermit
                        }
                        val response = try {
                            AirdHttp.putBytes(
                                context,
                                "/api/upload/range/${URLEncoder.encode(uploadId, "UTF-8")}",
                                bytes,
                                mapOf("Content-Range" to slice.contentRange),
                                cancelled,
                            )
                        } catch (err: Exception) {
                            val message = if (cancelled() || err.message?.contains("cancelled") == true) "cancelled" else err.message ?: "Could not upload"
                            failure.compareAndSet(null, FileOp(false, message))
                            return@withPermit
                        }
                        if (response.code == 0 || cancelled()) {
                            failure.compareAndSet(null, FileOp(false, "cancelled"))
                            return@withPermit
                        }
                        if (!response.ok) {
                            failure.compareAndSet(null, FileOp(false, fileMessage(response, "Could not upload")))
                            return@withPermit
                        }
                        onProgress(sent.addAndGet(bytes.size.toLong()), size)
                    }
                }
            }.awaitAll()
        }
        failure.get()?.let { failed ->
            if (failed.message == "cancelled") {
                try {
                    AirdHttp.send(context, "/api/upload/range/${URLEncoder.encode(uploadId, "UTF-8")}", "DELETE", JSONObject())
                } catch (_: Exception) {
                }
            }
            return failed
        }
        return FileOp(true, "")
    }

    suspend fun uploadToFolder(context: Context, dir: String, name: String, file: File): FileOp {
        val response = try {
            AirdHttp.postRaw(
                context,
                "/upload",
                file,
                mapOf(
                    "X-Upload-Dir" to headerEncode(dir.trim().trim('/')),
                    "X-Upload-Filename" to headerEncode(name),
                ),
            )
        } catch (err: Exception) {
            return FileOp(false, err.message ?: "Could not upload")
        }
        if (response.ok) return FileOp(true, "")
        return FileOp(false, fileMessage(response, "Could not upload"), response.code == 403)
    }

    private suspend fun coveredRanges(context: Context, uploadId: String): List<LongArray> {
        val response = try {
            AirdHttp.get(context, "/api/upload/range/${URLEncoder.encode(uploadId, "UTF-8")}/status")
        } catch (_: Exception) {
            return emptyList()
        }
        if (!response.ok) return emptyList()
        val ranges = response.json().optJSONArray("ranges") ?: return emptyList()
        return buildList {
            for (i in 0 until ranges.length()) {
                val pair = ranges.optJSONArray(i) ?: continue
                if (pair.length() >= 2) add(longArrayOf(pair.optLong(0), pair.optLong(1)))
            }
        }
    }

    private fun coveredBytes(ranges: List<LongArray>, size: Long): Long {
        var total = 0L
        ranges.forEach { range ->
            val start = range[0].coerceAtLeast(0)
            val end = range[1].coerceAtMost(size - 1)
            if (end >= start) total += end - start + 1
        }
        return total.coerceAtMost(size)
    }

    private fun rangeCovered(ranges: List<LongArray>, start: Long, end: Long): Boolean {
        var cursor = start
        ranges.sortedBy { it[0] }.forEach { range ->
            if (range[1] < cursor) return@forEach
            if (range[0] > cursor) return false
            cursor = maxOf(cursor, range[1] + 1)
            if (cursor > end) return true
        }
        return cursor > end
    }

    private fun readSlice(context: Context, uri: Uri, start: Long, len: Int): ByteArray {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val buf = java.nio.ByteBuffer.allocate(len)
                    channel.position(start)
                    while (buf.hasRemaining()) {
                        if (channel.read(buf) < 0) break
                    }
                    return buf.array().copyOf(buf.position())
                }
            }
        } catch (_: Exception) {
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            var left = start
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else if (input.read() < 0) {
                    break
                } else {
                    left -= 1
                }
            }
            val out = ByteArray(len)
            var got = 0
            while (got < len) {
                val n = input.read(out, got, len - got)
                if (n < 0) break
                got += n
            }
            if (got == len) out else out.copyOf(got)
        } ?: ByteArray(0)
    }

    private suspend fun formOp(context: Context, path: String, fields: Map<String, String>, fallback: String): FileOp {
        val response = try {
            AirdHttp.postForm(context, path, fields)
        } catch (err: Exception) {
            return FileOp(false, err.message ?: fallback)
        }
        if (response.ok) return FileOp(true, "")
        return FileOp(false, fileMessage(response, fallback), response.code == 403)
    }

    private fun fileMessage(response: AirdResponse, fallback: String): String {
        val json = response.json().optString("error")
        if (json.isNotBlank()) return json.take(180)
        val plain = response.body.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (plain.isBlank() || plain.startsWith("<")) return fallback
        return plain.take(180)
    }
}

data class AirdEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: String = "",
    val modified: String = "",
    val tags: List<String> = emptyList(),
    val shared: Boolean = false,
)

data class UserTag(val id: Int, val name: String, val files: List<String>)

data class TagCatalog(
    val names: List<String>,
    val colors: Map<String, String>,
    val tags: List<UserTag> = emptyList(),
)

data class FileOp(val ok: Boolean, val message: String, val forbidden: Boolean = false)

private fun JSONObject.toEntry(name: String, path: String): AirdEntry {
    val tags = buildList {
        val arr = optJSONArray("tags") ?: return@buildList
        for (i in 0 until arr.length()) {
            val tag = arr.optString(i)
            if (tag.isNotEmpty()) add(tag)
        }
    }
    return AirdEntry(
        name = name,
        path = path,
        dir = optBoolean("is_dir"),
        size = optString("size_str"),
        modified = optString("modified"),
        tags = tags,
        shared = optBoolean("is_shared"),
    )
}
