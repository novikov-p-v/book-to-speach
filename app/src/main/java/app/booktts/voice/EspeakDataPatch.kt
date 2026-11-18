package app.booktts.voice

import android.content.Context
import android.util.Log
import app.booktts.data.Settings
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Provides the espeak-ng data folder used by espeak-based models (Piper, Kokoro), with an improved
 * Russian dictionary.
 *
 * espeak often stresses rarer Russian word forms wrongly (стука́йте instead of сту́кайте). The bundled
 * assets/ru/ru_dict(.gz) is espeak's own Russian dictionary recompiled with extra "word $N" entries
 * (stress on vowel N) for every word where espeak disagreed with the RUAccent dictionary; homographs
 * are excluded. See tools/ru-stress.
 *
 * All sherpa-onnx models ship identical espeak-ng data and sherpa-onnx initialises espeak only once per
 * process, so instead of patching every model the app keeps one shared copy (filesDir/espeak-ng-data) with
 * the improved ru_dict and points every model at it. Models themselves are never modified. Changing the
 * setting takes effect after an app restart (espeak is already initialised).
 */
class EspeakDataPatch(private val context: Context, private val settings: Settings) {

    private val shared = File(context.filesDir, "espeak-ng-data")

    /** Whether this build bundles the improved dictionary. */
    val available: Boolean by lazy { assetName() != null }

    /** espeak-ng data folder to use for [model] (its own folder unless the improved dictionary is on). */
    @Synchronized
    fun dataDirFor(model: InstalledModel): String {
        val own = model.files["dataDir"] ?: return ""
        if (!available || !settings.ruStressDict) return own.absolutePath
        if (File(shared, MARKER).let { it.isFile && it.readText() == VERSION }) return shared.absolutePath
        return runCatching {
            val t0 = System.currentTimeMillis()
            val tmp = File(context.filesDir, "espeak-ng-data.tmp")
            tmp.deleteRecursively()
            own.copyRecursively(tmp, overwrite = true)
            val name = assetName()!!
            context.assets.open(name).use { raw ->
                val input = if (name.endsWith(".gz")) GZIPInputStream(raw, 1 shl 16) else raw
                File(tmp, "ru_dict").outputStream().use { input.copyTo(it) }
            }
            File(tmp, MARKER).writeText(VERSION)
            shared.deleteRecursively()
            check(tmp.renameTo(shared)) { "rename failed" }
            Log.i(TAG, "Prepared espeak data with improved Russian stress in ${System.currentTimeMillis() - t0} ms")
            shared.absolutePath
        }.getOrElse {
            Log.e(TAG, "Cannot prepare improved Russian dictionary, using the model's own data", it)
            own.absolutePath
        }
    }

    /** The build tools store assets/x.gz unpacked as x; accept either form. */
    private fun assetName(): String? = listOf(ASSET, "$ASSET.gz").firstOrNull { name ->
        runCatching { context.assets.open(name).close() }.isSuccess
    }

    companion object {
        private const val TAG = "EspeakData"
        private const val ASSET = "ru/ru_dict"
        private const val MARKER = "booktts-version"

        /** Bump when assets/ru/ru_dict.gz is regenerated so devices refresh their copy. */
        const val VERSION = "ru-stress-1"
    }
}
