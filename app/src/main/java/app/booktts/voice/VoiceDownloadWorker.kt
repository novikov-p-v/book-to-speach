package app.booktts.voice

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.booktts.BookTtsApp
import app.booktts.R
import app.booktts.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads a voice archive once, verifies its SHA-256 and extracts it. The only network use in the app. */
class VoiceDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val voices = applicationContext.container.voices
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val voice = voices.catalog.firstOrNull { it.id == id } ?: return@withContext Result.failure()
        val tmp = File(applicationContext.cacheDir, "$id.tar.bz2")
        try {
            setForeground(foregroundInfo(voice.name, 0))
            download(voice, tmp)
            setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STAGE to "extract"))
            setForeground(foregroundInfo(voice.name, -1))
            tmp.inputStream().use { voices.install(it, voice.meta) }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            tmp.delete()
            File(voices.modelsDir, "$id.tmp").deleteRecursively()
        }
    }

    private suspend fun download(voice: CatalogModel, dest: File) {
        var lastPct = -1
        downloadVerified(voice.url, dest, voice.sha256) { done ->
            val pct = (done * 100 / voice.sizeBytes.coerceAtLeast(1)).toInt().coerceIn(0, 99)
            if (pct != lastPct) {
                lastPct = pct
                setProgress(workDataOf(KEY_PROGRESS to pct, KEY_STAGE to "download"))
                if (pct % 5 == 0) setForeground(foregroundInfo(voice.name, pct))
            }
        }
    }

    private fun foregroundInfo(name: String, pct: Int): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, BookTtsApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.downloading_voice, name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .build()
        val notifId = 1000 + (inputData.getString(KEY_ID)?.hashCode() ?: 0) % 1000
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    companion object {
        const val TAG = "voice-download"
        const val KEY_ID = "id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"

        fun tagFor(id: String) = "voice:$id"

        fun enqueue(context: Context, id: String) {
            val req = OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .addTag(tagFor(id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("voice-$id", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("voice-$id")
        }
    }
}
