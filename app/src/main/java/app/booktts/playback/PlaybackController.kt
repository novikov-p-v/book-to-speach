package app.booktts.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.booktts.data.Book
import app.booktts.data.BookContent
import app.booktts.data.LibraryRepository
import app.booktts.data.Settings
import app.booktts.tts.TtsEngine
import app.booktts.text.SpeechTextPreparer
import app.booktts.voice.EspeakDataPatch
import app.booktts.voice.VoiceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class PlaybackState(
    val book: Book? = null,
    val content: BookContent? = null,
    val chunkIndex: Int = 0,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val voiceName: String? = null,
    /** Synthesis can't keep up with playback on this device (audio keeps running dry). */
    val slowVoice: Boolean = false,
) {
    val chapterIndex: Int get() = content?.chapterOf(chunkIndex) ?: 0
}

/**
 * Owns the reading session: synthesizes chunks ahead of time on a background coroutine
 * and streams them to an AudioTrack. UI and the media session both drive this object.
 */
class PlaybackController(
    private val context: Context,
    private val library: LibraryRepository,
    private val voices: VoiceRepository,
    private val settings: Settings,
    private val speechText: SpeechTextPreparer,
    private val espeakPatch: EspeakDataPatch,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = TtsEngine()
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private var job: Job? = null
    private var lastSavedIndex = -1
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "booktts:playback").apply { setReferenceCounted(false) }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = pause()
    }
    private var noisyRegistered = false

    fun open(bookId: String) {
        if (_state.value.book?.id == bookId) return
        pause()
        scope.launch {
            val book = library.get(bookId) ?: return@launch
            val content = runCatching { library.loadContent(bookId) }.getOrElse {
                _state.value = PlaybackState(book = book, error = "Cannot load book: ${it.message}")
                return@launch
            }
            library.update(bookId) { it.copy(lastOpenedAt = System.currentTimeMillis()) }
            _state.value = PlaybackState(
                book = library.get(bookId),
                content = content,
                chunkIndex = book.chunkIndex.coerceIn(0, (content.chunks.size - 1).coerceAtLeast(0)),
                voiceName = voices.voiceFor(book)?.label,
            )
        }
    }

    fun play() {
        val s = _state.value
        if (s.content == null || s.content.chunks.isEmpty()) return
        val start = if (s.ended) 0 else s.chunkIndex
        _state.update { it.copy(playWhenReady = true, ended = false, error = null, chunkIndex = start, buffering = true, slowVoice = false) }
        restart(start)
    }

    fun pause() {
        if (!_state.value.playWhenReady && job?.isActive != true) return
        _state.update { it.copy(playWhenReady = false, buffering = false) }
        job?.cancel() // the next restart() joins it, so two AudioTracks never overlap
        releaseAudioResources()
        saveProgress(force = true)
    }

    fun togglePlay() = if (_state.value.playWhenReady) pause() else play()

    fun seekTo(index: Int) {
        val content = _state.value.content ?: return
        val i = index.coerceIn(0, content.chunks.lastIndex)
        _state.update { it.copy(chunkIndex = i, ended = false) }
        if (_state.value.playWhenReady) restart(i) else saveProgress(force = true)
    }

    fun seekToChapter(chapter: Int) {
        val content = _state.value.content ?: return
        content.chapters.getOrNull(chapter)?.let { seekTo(it.start) }
    }

    fun nextChapter() {
        val s = _state.value
        val content = s.content ?: return
        if (s.chapterIndex + 1 < content.chapters.size) seekToChapter(s.chapterIndex + 1)
    }

    fun prevChapter() {
        val s = _state.value
        val content = s.content ?: return
        val start = content.chapters[s.chapterIndex].start
        if (s.chunkIndex - start > 2 || s.chapterIndex == 0) seekTo(start) else seekToChapter(s.chapterIndex - 1)
    }

    fun nextChunk() = seekTo(_state.value.chunkIndex + 1)
    fun prevChunk() = seekTo(_state.value.chunkIndex - 1)

    fun setSpeed(speed: Float) {
        settings.speed = speed.coerceIn(0.5f, 3f)
        if (_state.value.playWhenReady) restart(_state.value.chunkIndex)
    }

    fun setBookVoice(voiceId: String?) {
        val book = _state.value.book ?: return
        library.update(book.id) { it.copy(voiceId = voiceId) }
        val updated = library.get(book.id) ?: return
        _state.update { it.copy(book = updated, voiceName = voices.voiceFor(updated)?.label, error = null) }
        if (_state.value.playWhenReady) restart(_state.value.chunkIndex)
    }

    /** Called when a book is deleted or voices change. */
    fun close(bookId: String) {
        if (_state.value.book?.id == bookId) {
            pause()
            _state.value = PlaybackState()
        }
    }

    fun refreshVoice() {
        val book = _state.value.book ?: return
        _state.update { it.copy(voiceName = voices.voiceFor(book)?.label) }
    }

    private fun restart(from: Int) {
        val old = job
        acquireAudioResources()
        job = scope.launch {
            old?.cancelAndJoin()
            try {
                runPipeline(from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Playback failed", e)
                _state.update { it.copy(playWhenReady = false, buffering = false, error = e.message ?: e.javaClass.simpleName) }
                releaseAudioResources()
            }
        }
    }

    private suspend fun runPipeline(from: Int) = coroutineScope {
        val s = _state.value
        val book = s.book ?: return@coroutineScope
        val content = s.content ?: return@coroutineScope
        val voice = voices.voiceFor(book)
        if (voice == null) {
            _state.update {
                it.copy(playWhenReady = false, buffering = false, error = NO_VOICE_PREFIX + "\"${book.lang}\". Download one in Voices.")
            }
            releaseAudioResources()
            return@coroutineScope
        }
        _state.update { it.copy(buffering = true, voiceName = voice.label) }
        espeakPatch.apply(voice.model)
        engine.load(voice, settings.threadsFor(voice.model.type))

        val speed = settings.speed
        val sampleRate = engine.sampleRate
        val pauseFrames = sampleRate * settings.paragraphPauseMs / 1000
        val sentenceFrames = sampleRate * 120 / 1000

        // Producer: synthesize a few chunks ahead of playback.
        val channel = Channel<Pair<Int, FloatArray>>(capacity = 3)
        launch(Dispatchers.Default) {
            try {
                for (i in from until content.chunks.size) {
                    val chunk = content.chunks[i]
                    val text = speechText.prepare(chunk.text, book.lang)
                    val pcm = engine.generate(text, voice.sid, speed, book.lang, settings.qualitySteps)
                    val gap = if (chunk.paragraphEnd || chunk.heading) pauseFrames else sentenceFrames
                    channel.send(i to pcm.copyOf(pcm.size + gap))
                }
            } finally {
                channel.close()
            }
        }

        val track = buildTrack(sampleRate)
        var written = 0L
        var underruns = 0
        var started = false
        try {
            track.play()
            while (true) {
                val polled = channel.tryReceive()
                val item = when {
                    polled.isSuccess -> polled.getOrThrow()
                    polled.isClosed -> null
                    else -> {
                        // Waiting for synthesis after playback started = the voice is slower than real time.
                        if (started && ++underruns >= 2) _state.update { it.copy(slowVoice = true) }
                        _state.update { it.copy(buffering = true) }
                        channel.receiveCatching().getOrNull()
                    }
                } ?: break
                val (index, pcm) = item
                _state.update { it.copy(chunkIndex = index, buffering = false) }
                saveProgress()
                started = true
                written += writeAll(track, pcm)
            }
            // wait for the tail of the audio to be played out
            while (isActive && track.playbackHeadPosition.toLong() and 0xFFFFFFFFL < written) delay(50)
            _state.update { it.copy(playWhenReady = false, ended = true, buffering = false) }
            saveProgress(force = true)
            releaseAudioResources()
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    private suspend fun writeAll(track: AudioTrack, pcm: FloatArray): Int {
        val step = engine.sampleRate / 10
        var off = 0
        while (off < pcm.size) {
            coroutineContext.ensureActive()
            val n = minOf(step, pcm.size - off)
            val w = track.write(pcm, off, n, AudioTrack.WRITE_BLOCKING)
            if (w < 0) error("AudioTrack write error $w")
            off += w
        }
        return pcm.size
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, (sampleRate / 4) * FLOAT_FRAME)) // ~250 ms, frame-aligned
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun saveProgress(force: Boolean = false) {
        val s = _state.value
        val book = s.book ?: return
        if (!force && kotlin.math.abs(s.chunkIndex - lastSavedIndex) < 3) return
        lastSavedIndex = s.chunkIndex
        library.update(book.id) { it.copy(chunkIndex = s.chunkIndex) }
    }

    @Synchronized
    private fun acquireAudioResources() {
        audioManager.requestAudioFocus(focusRequest)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        wakeLock.acquire(6 * 60 * 60 * 1000L)
    }

    @Synchronized
    private fun releaseAudioResources() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        private const val TAG = "Playback"
        private const val FLOAT_FRAME = 4 // mono float32
        const val NO_VOICE_PREFIX = "No voice installed for language "
        private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
