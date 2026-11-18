package app.booktts.text

import android.content.Context
import android.util.Log
import app.booktts.data.Settings
import java.io.File

/** Language-specific text fixes applied right before synthesis (the displayed text is unchanged). */
class SpeechTextPreparer(private val context: Context, private val settings: Settings) {
    @Volatile
    private var yo: RussianYo? = null
    private var yoFailed = false

    fun prepare(text: String, lang: String): String {
        if (lang != "ru" || !settings.restoreYo) return text
        return russianYo()?.process(text) ?: text
    }

    /** Compiles the bundled ё dictionary on first use (~1 s), then memory-maps it. */
    private fun russianYo(): RussianYo? {
        yo?.let { return it }
        synchronized(this) {
            yo?.let { return it }
            if (yoFailed) return null
            return runCatching {
                val file = File(context.filesDir, "ru/" + RussianYo.YO_FILE + ".v1")
                if (!file.isFile) {
                    file.parentFile?.mkdirs()
                    val t0 = System.currentTimeMillis()
                    // the build tools store assets/*.json.gz unpacked as *.json (the APK zip compresses them)
                    fun asset(name: String) = { context.assets.open("ru/$name").reader(Charsets.UTF_8) }
                    RussianYo.build(asset("yo_words.json"), asset("yo_homographs.json"), file)
                    Log.i(TAG, "Built ё dictionary in ${System.currentTimeMillis() - t0} ms")
                }
                RussianYo.open(file)
            }.onFailure {
                Log.e(TAG, "ё dictionary unavailable", it)
                yoFailed = true
            }.getOrNull().also { yo = it }
        }
    }

    companion object {
        private const val TAG = "SpeechText"
    }
}
