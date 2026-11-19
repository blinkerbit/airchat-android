package com.aird.transfer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aird.chat.data.FilesRepository
import com.aird.data.db.BackupItemEntity
import com.aird.data.db.BackupStatus
import com.aird.data.db.LocalStore
import com.aird.session.Session
import java.util.concurrent.TimeUnit

object CameraBackup {
    private const val PERIODIC = "aird-camera-backup"
    private const val ONCE = "aird-camera-backup-once"

    class Prefs(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences("aird", Context.MODE_PRIVATE)
        var enabled: Boolean
            get() = prefs.getBoolean(KEY_ENABLED, false)
            set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        var wifiOnly: Boolean
            get() = prefs.getBoolean(KEY_WIFI, true)
            set(value) = prefs.edit().putBoolean(KEY_WIFI, value).apply()
        var chargingOnly: Boolean
            get() = prefs.getBoolean(KEY_CHARGE, true)
            set(value) = prefs.edit().putBoolean(KEY_CHARGE, value).apply()
        var photos: Boolean
            get() = prefs.getBoolean(KEY_PHOTOS, true)
            set(value) = prefs.edit().putBoolean(KEY_PHOTOS, value).apply()
        var videos: Boolean
            get() = prefs.getBoolean(KEY_VIDEOS, true)
            set(value) = prefs.edit().putBoolean(KEY_VIDEOS, value).apply()
        var folder: String
            get() = prefs.getString(KEY_FOLDER, "Camera Backup").orEmpty().ifBlank { "Camera Backup" }
            set(value) = prefs.edit().putString(KEY_FOLDER, value).apply()
    }

    fun schedule(context: Context, immediate: Boolean = false) {
        val prefs = Prefs(context)
        if (!prefs.enabled && !immediate) {
            cancel(context)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(prefs.chargingOnly)
            .build()
        val manager = WorkManager.getInstance(context.applicationContext)
        if (immediate) {
            manager.enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CameraBackupWorker>().setConstraints(constraints).build(),
            )
        }
        if (prefs.enabled) {
            manager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CameraBackupWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(ONCE)
    }

    private const val KEY_ENABLED = "camera_backup_enabled"
    private const val KEY_WIFI = "camera_backup_wifi"
    private const val KEY_CHARGE = "camera_backup_charging"
    private const val KEY_PHOTOS = "camera_backup_photos"
    private const val KEY_VIDEOS = "camera_backup_videos"
    private const val KEY_FOLDER = "camera_backup_folder"
}

class CameraBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = CameraBackup.Prefs(applicationContext)
        val user = Session.username.value
        if (user.isBlank()) return Result.retry()
        val db = LocalStore.db(applicationContext, user) ?: return Result.retry()
        scan(prefs, db)
        val pending = db.backup().pending(8)
        if (pending.isEmpty()) return Result.success()
        FilesRepository.createFolder(applicationContext, "", prefs.folder)
        var failures = 0
        for (item in pending) {
            db.backup().mark(item.mediaId, BackupStatus.UPLOADING, 0, System.currentTimeMillis())
            val uri = Uri.parse(item.uri)
            val op = try {
                FilesRepository.uploadUri(
                    applicationContext,
                    prefs.folder,
                    item.name,
                    uri,
                    item.size,
                    { isStopped },
                ) { _, _ -> }
            } catch (_: Exception) {
                null
            }
            if (op?.ok == true) {
                db.backup().mark(item.mediaId, BackupStatus.DONE, 0, System.currentTimeMillis())
            } else {
                failures++
                db.backup().mark(item.mediaId, BackupStatus.FAILED, 1, System.currentTimeMillis())
            }
            if (isStopped) return Result.retry()
        }
        return if (failures > 0) Result.retry() else Result.success()
    }

    private suspend fun scan(prefs: CameraBackup.Prefs, db: com.aird.data.db.AirdDatabase) {
        val known = db.backup().knownIds().toHashSet()
        val rows = mutableListOf<BackupItemEntity>()
        if (prefs.photos) rows += query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false, known)
        if (prefs.videos) rows += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true, known)
        if (rows.isNotEmpty()) db.backup().upsert(rows)
    }

    private fun query(collection: Uri, video: Boolean, known: Set<Long>): List<BackupItemEntity> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val sort = MediaStore.MediaColumns.DATE_TAKEN + " DESC"
        val out = mutableListOf<BackupItemEntity>()
        applicationContext.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            var count = 0
            while (cursor.moveToNext() && count < 200) {
                val id = cursor.getLong(idIdx)
                if (id in known) continue
                val name = cursor.getString(nameIdx).orEmpty().ifBlank { if (video) "video-$id.mp4" else "photo-$id.jpg" }
                val uri = ContentUris.withAppendedId(collection, id).toString()
                out += BackupItemEntity(
                    mediaId = id,
                    uri = uri,
                    name = name,
                    size = cursor.getLong(sizeIdx),
                    takenAt = cursor.getLong(takenIdx),
                    video = video,
                    status = BackupStatus.PENDING,
                    attempts = 0,
                    updatedAt = System.currentTimeMillis(),
                )
                count++
            }
        }
        return out
    }
}
