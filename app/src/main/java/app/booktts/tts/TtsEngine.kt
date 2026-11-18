package app.booktts.tts

import android.util.Log
import app.booktts.voice.ModelType
import app.booktts.voice.Voice
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe wrapper over a sherpa-onnx OfflineTts instance (one loaded model at a time).
 * Supports Piper (VITS), Kokoro and Supertonic model families.
 */
class TtsEngine {
    private val mutex = Mutex()
    private var tts: OfflineTts? = null
    private var loadedKey: String? = null
    private var loadedType: String = ModelType.VITS

    @Volatile
    var sampleRate: Int = 22050
        private set

    /** @param espeakDataDir espeak-ng data folder for Piper/Kokoro (see EspeakDataPatch). */
    suspend fun load(voice: Voice, threads: Int, espeakDataDir: String) = mutex.withLock {
        val m = voice.model
        // Kokoro uses a different pronunciation lexicon for US and GB voices.
        val lexicon = when (m.type) {
            ModelType.KOKORO -> if (voice.speaker?.accent == "gb") m.file("lexicon-gb") else m.file("lexicon-us")
            ModelType.VITS -> m.file("lexicon")
            else -> ""
        }
        val key = "${m.id}/$lexicon/$threads/$espeakDataDir"
        if (key == loadedKey && tts != null) return@withLock
        tts?.release()
        tts = null
        loadedKey = null

        val modelConfig = when (m.type) {
            ModelType.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = m.file("durationPredictor"),
                    textEncoder = m.file("textEncoder"),
                    vectorEstimator = m.file("vectorEstimator"),
                    vocoder = m.file("vocoder"),
                    ttsJson = m.file("ttsJson"),
                    unicodeIndexer = m.file("unicodeIndexer"),
                    voiceStyle = m.file("voiceStyle"),
                ),
                numThreads = threads, debug = false, provider = "cpu",
            )
            ModelType.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = m.file("model"),
                    voices = m.file("voices"),
                    tokens = m.file("tokens"),
                    dataDir = espeakDataDir,
                    lexicon = lexicon,
                ),
                numThreads = threads, debug = false, provider = "cpu",
            )
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = m.file("model"),
                    tokens = m.file("tokens"),
                    dataDir = espeakDataDir,
                    lexicon = lexicon,
                ),
                numThreads = threads, debug = false, provider = "cpu",
            )
        }
        val config = OfflineTtsConfig(model = modelConfig, maxNumSentences = 1)
        val t0 = System.currentTimeMillis()
        val instance = withContext(Dispatchers.IO) { OfflineTts(config = config) }
        Log.i(TAG, "Loaded ${m.id} (${m.type}) in ${System.currentTimeMillis() - t0} ms, ${instance.numSpeakers()} speakers")
        tts = instance
        sampleRate = instance.sampleRate()
        loadedKey = key
        loadedType = m.type
    }

    /**
     * Synthesizes [text]; returns mono float PCM at [sampleRate].
     * [lang] is used by multilingual models (Supertonic); [steps] trades speed for quality there.
     */
    suspend fun generate(text: String, sid: Int, speed: Float, lang: String, steps: Int = DEFAULT_STEPS): FloatArray =
        mutex.withLock {
            val t = tts ?: error("No voice loaded")
            val t0 = System.nanoTime()
            val samples = when (loadedType) {
                ModelType.SUPERTONIC -> t.generateWithConfig(
                    text,
                    GenerationConfig(
                        sid = sid,
                        speed = speed,
                        numSteps = steps,
                        // our chunks are already short; keep one synthesis pass per chunk
                        extra = mapOf("lang" to lang, "max_len" to "400"),
                    ),
                ).samples
                else -> t.generate(text, sid, speed).samples
            }
            val audioMs = samples.size * 1000L / sampleRate
            val synthMs = (System.nanoTime() - t0) / 1_000_000
            Log.d(TAG, "RTF=${"%.2f".format(synthMs.toFloat() / audioMs.coerceAtLeast(1))} ($synthMs ms for $audioMs ms)")
            samples
        }

    suspend fun release() = mutex.withLock {
        tts?.release()
        tts = null
        loadedKey = null
    }

    companion object {
        private const val TAG = "TtsEngine"
        const val DEFAULT_STEPS = 5
    }
}
