package app.booktts

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.booktts.data.LibraryRepository
import app.booktts.data.Settings
import app.booktts.playback.PlaybackController
import app.booktts.tts.VoicePreview
import app.booktts.text.SpeechTextPreparer
import app.booktts.voice.EspeakDataPatch
import app.booktts.voice.VoiceRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AppContainer(context: Context) {
    val settings = Settings(context)
    val library = LibraryRepository(context)
    val voices = VoiceRepository(context, settings)
    val speechText = SpeechTextPreparer(context, settings)
    val espeakPatch = EspeakDataPatch(context, settings)
    val playback = PlaybackController(context, library, voices, settings, speechText, espeakPatch)
    val preview = VoicePreview(settings, speechText, espeakPatch)
}

class BookTtsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, getString(R.string.channel_work), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_WORK = "work"
    }
}

val Context.container: AppContainer get() = (applicationContext as BookTtsApp).container
