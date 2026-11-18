package app.booktts.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.booktts.BookTtsApp
import app.booktts.R
import app.booktts.container
import app.booktts.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts a book (or selected chapters) into one .m4a file per chapter inside a user-chosen folder.
 * Already exported chapters are skipped, so a cancelled export can be resumed.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val c = applicationContext.container
        val bookId = inputData.getString(KEY_BOOK) ?: return@withContext Result.failure()
        val tree = inputData.getString(KEY_TREE)?.let(Uri::parse) ?: return@withContext Result.failure()
        val book = c.library.get(bookId) ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Book not found"))
        val voice = c.voices.voiceFor(book)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "No voice installed for ${book.lang}"))
        val content = c.library.loadContent(bookId)

        val root = DocumentFile.fromTreeUri(applicationContext, tree)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Folder not accessible"))
        val folderName = sanitize(book.title)
        val folder = root.findFile(folderName)?.takeIf { it.isDirectory } ?: root.createDirectory(folderName)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Cannot create folder"))

        val selected = inputData.getIntArray(KEY_CHAPTERS)
            ?.filter { it in content.chapters.indices }?.distinct()?.sorted()
            ?: content.chapters.indices.toList()
        val engine = TtsEngine()
        val total = selected.size
        try {
            setForeground(foregroundInfo(book.title, 0, total))
            engine.load(voice, c.settings.threadsFor(voice.model.type), c.espeakPatch.dataDirFor(voice.model))
            val speed = c.settings.speed
            val sr = engine.sampleRate
            val pauseFrames = sr * c.settings.paragraphPauseMs / 1000
            val gapFrames = sr * 120 / 1000

            for ((n, i) in selected.withIndex()) {
                val chapter = content.chapters[i]
                coroutineContext.ensureActive()
                setProgress(workDataOf(KEY_DONE to n, KEY_TOTAL to total))
                setForeground(foregroundInfo(book.title, n, total))
                val name = "%03d - %s.m4a".format(i + 1, sanitize(chapter.title)).take(120)
                val existing = folder.findFile(name)
                if (existing != null && existing.length() > 0) continue

                val tmp = File(applicationContext.cacheDir, "export_$i.m4a")
                val encoder = AacEncoder(tmp, sr)
                try {
                    for (ci in content.chapterRange(i)) {
                        coroutineContext.ensureActive()
                        val chunk = content.chunks[ci]
                        val text = c.speechText.prepare(chunk.text, book.lang)
                        val pcm = engine.generate(text, voice.sid, speed, book.lang, c.settings.qualitySteps)
                        val gap = if (chunk.paragraphEnd || chunk.heading) pauseFrames else gapFrames
                        encoder.write(pcm.copyOf(pcm.size + gap))
                    }
                    encoder.finish()
                } catch (e: Throwable) {
                    encoder.release()
                    tmp.delete()
                    throw e
                }
                existing?.delete()
                val out = folder.createFile("audio/mp4", name)
                    ?: error("Cannot create $name")
                applicationContext.contentResolver.openOutputStream(out.uri)!!.use { os ->
                    tmp.inputStream().use { it.copyTo(os) }
                }
                tmp.delete()
            }
            setProgress(workDataOf(KEY_DONE to total, KEY_TOTAL to total))
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            engine.release()
        }
    }

    private fun sanitize(s: String) = s.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().ifEmpty { "book" }

    private fun foregroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, BookTtsApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.exporting, title))
            .setContentText("$done / $total")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, false)
            .addAction(0, applicationContext.getString(R.string.cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    companion object {
        private const val NOTIF_ID = 2001
        const val KEY_BOOK = "book"
        const val KEY_TREE = "tree"
        const val KEY_CHAPTERS = "chapters"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        fun uniqueName(bookId: String) = "export-$bookId"

        /** [chapters] = chapter indices to export; null exports the whole book. */
        fun enqueue(context: Context, bookId: String, tree: Uri, chapters: IntArray? = null) {
            val data = workDataOf(KEY_BOOK to bookId, KEY_TREE to tree.toString())
                .let { d -> if (chapters == null) d else androidx.work.Data.Builder().putAll(d).putIntArray(KEY_CHAPTERS, chapters).build() }
            val req = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(data)
                .addTag("export")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(bookId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, bookId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(bookId))
        }
    }
}
