package com.aird.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aird.chat.data.ChatRepository
import com.aird.chat.data.CollabRepository
import com.aird.session.AirdHttp
import com.aird.session.Session
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class FileUploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uriText = inputData.getString(KEY_URI) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val dir = inputData.getString(KEY_DIR).orEmpty()
        val size = inputData.getLong(KEY_SIZE, -1)
        val uri = Uri.parse(uriText)
        setForeground(foreground(0f))
        return try {
            val op = ChatRepository.uploadUri(
                applicationContext,
                dir,
                name,
                uri,
                size,
                { isStopped },
            ) { loaded, total ->
                val fraction = if (total <= 0) 0f else loaded.toFloat() / total.toFloat()
                setProgressAsync(workDataOf("loaded" to loaded, "total" to total))
                setForegroundAsync(foreground(fraction))
            }
            when {
                op.ok -> Result.success()
                op.message == "cancelled" -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: CancellationException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    private fun foreground(progress: Float): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Uploading to Aird")
            .setContentText("$percent% completed")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        val id = NOTIFICATION_BASE + (inputData.getString(KEY_URI).hashCode() and 0xffff)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val KEY_URI = "file_uri"
        const val KEY_NAME = "file_name"
        const val KEY_DIR = "upload_dir"
        const val KEY_SIZE = "file_size"
        private const val CHANNEL = "aird_transfers"
        private const val NOTIFICATION_BASE = 4100

        fun enqueue(context: Context, uri: Uri, dir: String, name: String, size: Long): java.util.UUID {
            val work = androidx.work.OneTimeWorkRequestBuilder<FileUploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URI to uri.toString(),
                        KEY_NAME to name,
                        KEY_DIR to dir,
                        KEY_SIZE to size,
                    ),
                )
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(work)
            return work.id
        }
    }
}

class ListCatchUpWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val listId = inputData.getString(KEY_LIST) ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getString(listId, "").orEmpty()
        val actor = Session.username.value.ifBlank { "android" }
        val response = try {
            AirdHttp.send(
                applicationContext,
                "/api/lists/$listId/sync",
                "POST",
                JSONObject().put("actor_id", actor).put("since_hlc", since).put("ops", JSONArray()),
            )
        } catch (_: Exception) {
            return Result.retry()
        }
        if (!response.ok) return Result.retry()
        val tip = response.json().optString("tip_hlc")
        if (tip.isNotBlank()) prefs.edit().putString(listId, tip).apply()
        try {
            CollabRepository.refresh(applicationContext, "list", listId, "")
        } catch (_: Exception) {
        }
        return Result.success()
    }

    companion object {
        private const val KEY_LIST = "list_id"
        private const val PREFS = "aird_list_sync"

        fun schedule(context: Context, listId: String) {
            val request = PeriodicWorkRequestBuilder<ListCatchUpWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_LIST to listId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "aird-list-$listId",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
