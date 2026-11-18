package app.booktts.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _speed = MutableStateFlow(prefs.getFloat("speed", 1.0f))
    val speedFlow: StateFlow<Float> = _speed
    var speed: Float
        get() = _speed.value
        set(v) {
            _speed.value = v
            prefs.edit().putFloat("speed", v).apply()
        }

    var paragraphPauseMs: Int
        get() = prefs.getInt("paragraphPauseMs", 400)
        set(v) = prefs.edit().putInt("paragraphPauseMs", v).apply()

    /** Use the improved Russian stress dictionary for espeak (Piper voices). Applies after restart. */
    private val _ruStressDict = MutableStateFlow(prefs.getBoolean("ruStressDict", true))
    val ruStressDictFlow: StateFlow<Boolean> = _ruStressDict
    var ruStressDict: Boolean
        get() = _ruStressDict.value
        set(v) {
            _ruStressDict.value = v
            prefs.edit().putBoolean("ruStressDict", v).apply()
        }

    /** Restore ё in Russian text before synthesis (еще -> ещё). */
    private val _restoreYo = MutableStateFlow(prefs.getBoolean("restoreYo", true))
    val restoreYoFlow: StateFlow<Boolean> = _restoreYo
    var restoreYo: Boolean
        get() = _restoreYo.value
        set(v) {
            _restoreYo.value = v
            prefs.edit().putBoolean("restoreYo", v).apply()
        }

    /**
     * Supertonic denoising steps: more = better quality, slower. Ignored by other models.
     */
    private val _qualitySteps = MutableStateFlow(prefs.getInt("qualitySteps", 5))
    val qualityStepsFlow: StateFlow<Int> = _qualitySteps
    var qualitySteps: Int
        get() = _qualitySteps.value
        set(v) {
            _qualitySteps.value = v
            prefs.edit().putInt("qualitySteps", v).apply()
        }

    /**
     * Threads for synthesis. Piper is light (half the cores is plenty); Kokoro/Supertonic are heavy,
     * so they get more cores (phones usually have 4 fast cores, more threads than that rarely helps).
     */
    fun threadsFor(modelType: String): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (modelType == "vits") (cores / 2).coerceIn(1, 4) else (cores - 1).coerceIn(1, 4)
    }

    private val _defaults = MutableStateFlow(readDefaults())
    val defaultVoices: StateFlow<Map<String, String>> = _defaults

    fun defaultVoice(lang: String): String? = _defaults.value[lang]

    fun setDefaultVoice(lang: String, voiceId: String) {
        prefs.edit().putString("defaultVoice_$lang", voiceId).apply()
        _defaults.value = readDefaults()
    }

    private fun readDefaults(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith("defaultVoice_") }
        .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix("defaultVoice_") to it } }
        .toMap()
}
