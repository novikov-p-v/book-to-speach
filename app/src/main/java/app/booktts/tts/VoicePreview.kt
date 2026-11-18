package app.booktts.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.booktts.data.Settings
import app.booktts.text.SpeechTextPreparer
import app.booktts.voice.EspeakDataPatch
import app.booktts.voice.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Plays a short sample sentence with a given voice, so voices can be compared. */
class VoicePreview(private val settings: Settings, private val speechText: SpeechTextPreparer,
    private val espeakPatch: EspeakDataPatch,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = TtsEngine()
    private var job: Job? = null

    /** Key of the voice currently loading/playing a preview, or null. */
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active

    fun keyOf(voice: Voice, lang: String) = "${voice.ref.encode()}/$lang"

    fun play(voice: Voice, lang: String) {
        val key = keyOf(voice, lang)
        val old = job
        _active.value = key
        job = scope.launch {
            old?.cancelAndJoin()
            try {
                engine.load(voice, settings.threadsFor(voice.model.type), espeakPatch.dataDirFor(voice.model))
                val sampleLang = if (lang in samples) lang else "en"
                val text = speechText.prepare(samples.getValue(sampleLang), sampleLang)
                val pcm = engine.generate(text, voice.sid, settings.speed, sampleLang, settings.qualitySteps)
                playPcm(pcm, engine.sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("VoicePreview", "Preview failed", e)
            } finally {
                if (_active.value == key) _active.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        _active.value = null
    }

    /** Frees the preview model (call when leaving voice settings). */
    fun close() {
        stop()
        val old = job
        scope.launch {
            old?.cancelAndJoin()
            engine.release()
        }
    }

    private suspend fun playPcm(pcm: FloatArray, sampleRate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes((sampleRate / 4) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val step = sampleRate / 10
            var off = 0
            while (off < pcm.size) {
                coroutineContext.ensureActive()
                val w = track.write(pcm, off, minOf(step, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                if (w < 0) break
                off += w
            }
            while ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < pcm.size) {
                coroutineContext.ensureActive()
                delay(50)
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    companion object {
        private val samples = mapOf(
            "ru" to "Здравствуйте! Так звучит этот голос, когда он читает книгу вслух.",
            "en" to "Hello! This is how this voice sounds when it reads a book aloud.",
            "uk" to "Добрий день! Так звучить цей голос, коли він читає книжку вголос.",
            "de" to "Hallo! So klingt diese Stimme, wenn sie ein Buch vorliest.",
            "fr" to "Bonjour ! Voici comment cette voix lit un livre à haute voix.",
            "es" to "¡Hola! Así suena esta voz cuando lee un libro en voz alta.",
            "it" to "Ciao! Ecco come suona questa voce quando legge un libro ad alta voce.",
            "pl" to "Dzień dobry! Tak brzmi ten głos, gdy czyta książkę na głos.",
        )
    }
}
