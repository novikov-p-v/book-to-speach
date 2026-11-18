package app.booktts.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.booktts.data.Book
import app.booktts.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Model families supported by the engine. */
object ModelType {
    const val VITS = "vits" // Piper
    const val KOKORO = "kokoro"
    const val SUPERTONIC = "supertonic"
}

@Serializable
data class Speaker(
    val sid: Int,
    val name: String,
    /** Kokoro: "us" or "gb" English pronunciation lexicon. */
    val accent: String? = null,
)

/** A downloadable model from the bundled catalog (assets/voices.json). */
@Serializable
data class CatalogModel(
    val id: String,
    val type: String = ModelType.VITS,
    val langs: List<String>,
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String = "",
    /** Empty = single-speaker model (sid 0). */
    val speakers: List<Speaker> = emptyList(),
) {
    val meta get() = ModelMeta(id, type, langs, name, speakers)
}

/** Stored next to an installed model as voice.json. */
@Serializable
data class ModelMeta(
    val id: String,
    val type: String = ModelType.VITS,
    val langs: List<String>,
    val name: String,
    val speakers: List<Speaker> = emptyList(),
)

/** A model extracted on the device, with resolved file paths. */
data class InstalledModel(
    val meta: ModelMeta,
    val dir: File,
    /** Role -> file, e.g. "model", "tokens", "dataDir", "voices", "lexicon-us"... */
    val files: Map<String, File>,
) {
    val id get() = meta.id
    val type get() = meta.type
    val name get() = meta.name
    fun file(role: String): String = files[role]?.absolutePath ?: ""
}

/** What the user picks: a model plus one of its speakers. Stored as "modelId#sid". */
data class VoiceRef(val modelId: String, val sid: Int = 0) {
    fun encode() = "$modelId#$sid"

    companion object {
        fun decode(s: String): VoiceRef {
            val i = s.lastIndexOf('#')
            return if (i < 0) VoiceRef(s) else VoiceRef(s.substring(0, i), s.substring(i + 1).toIntOrNull() ?: 0)
        }
    }
}

/** A voice resolved against installed models, ready for the engine. */
data class Voice(val model: InstalledModel, val speaker: Speaker?) {
    val ref get() = VoiceRef(model.id, speaker?.sid ?: 0)
    val sid get() = speaker?.sid ?: 0
    val label: String get() = if (speaker == null) model.name else "${model.name.substringBefore(" — ")}: ${speaker.name}"
}

private val json = Json { ignoreUnknownKeys = true }

/** Display name of a language in that language, e.g. "Русский". */
fun languageName(code: String): String {
    val loc = Locale.forLanguageTag(code)
    val name = loc.getDisplayLanguage(loc)
    return if (name.isEmpty() || name == code) code else name.replaceFirstChar { it.titlecase(loc) }
}

class VoiceRepository(private val context: Context, private val settings: Settings) {
    val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    val catalog: List<CatalogModel> by lazy {
        context.assets.open("voices.json").use {
            json.decodeFromString(ListSerializer(CatalogModel.serializer()), it.readBytes().decodeToString())
        }
    }

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<List<InstalledModel>> = _installed

    fun refresh() {
        _installed.value = scan()
    }

    fun find(id: String): InstalledModel? = _installed.value.firstOrNull { it.id == id }

    fun resolve(ref: VoiceRef): Voice? {
        val model = find(ref.modelId) ?: return null
        if (model.meta.speakers.isEmpty()) return Voice(model, null)
        val sp = model.meta.speakers.firstOrNull { it.sid == ref.sid } ?: return null
        return Voice(model, sp)
    }

    /** All installed voices that can read [lang], best models first. */
    fun voicesFor(lang: String): List<Voice> =
        _installed.value
            .filter { lang in it.meta.langs }
            .sortedBy { if (lang == "en" && it.type == ModelType.KOKORO) -1 else typeRank(it.type) }
            .flatMap { m -> if (m.meta.speakers.isEmpty()) listOf(Voice(m, null)) else m.meta.speakers.map { Voice(m, it) } }

    fun defaultFor(lang: String): Voice? =
        settings.defaultVoice(lang)?.let { resolve(VoiceRef.decode(it)) } ?: voicesFor(lang).firstOrNull()

    fun voiceFor(book: Book): Voice? =
        book.voiceId?.let { resolve(VoiceRef.decode(it)) }?.takeIf { book.lang in it.model.meta.langs }
            ?: defaultFor(book.lang)

    fun delete(id: String) {
        File(modelsDir, id).deleteRecursively()
        refresh()
    }

    /** Installs a sherpa-onnx model archive (.tar.bz2) picked by the user. */
    suspend fun importArchive(uri: Uri): InstalledModel = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "voice.tar.bz2"
        val base = name.removeSuffix(".tar.bz2")
        val id = base.removePrefix("vits-piper-")
        val meta = catalog.firstOrNull { it.id == id || it.url.endsWith("/$name") }?.meta
            ?: run {
                val lang = Regex("^([a-z]{2})_[A-Z]{2}").find(id)?.groupValues?.get(1) ?: "en"
                ModelMeta(id, ModelType.VITS, listOf(lang), id)
            }
        context.contentResolver.openInputStream(uri)!!.use { install(it, meta) }
    }

    /** Extracts a .tar.bz2 model archive into models/<id>. */
    fun install(input: InputStream, meta: ModelMeta): InstalledModel {
        val tmp = File(modelsDir, "${meta.id}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(tmp, entry.name)
                require(out.canonicalPath.startsWith(root)) { "Bad archive entry: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isFile) {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        // Imported archives: detect the model family from its files.
        val detected = if (meta.type == ModelType.VITS) meta.copy(type = detectType(tmp)) else meta
        File(tmp, "voice.json").writeText(json.encodeToString(ModelMeta.serializer(), detected))
        requireNotNull(locate(tmp, detected)) { "Archive does not contain a supported TTS model" }
        val dest = File(modelsDir, meta.id)
        dest.deleteRecursively()
        check(tmp.renameTo(dest)) { "Cannot move model into place" }
        refresh()
        return find(meta.id)!!
    }

    private fun scan(): List<InstalledModel> =
        modelsDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".tmp") }
            .mapNotNull { dir ->
                val meta = runCatching { readMeta(File(dir, "voice.json").readText()) }.getOrNull()
                    ?: return@mapNotNull null
                locate(dir, meta)
            }
            .sortedWith(compareBy({ typeRank(it.type) }, { it.name }))

    /** Reads voice.json, including the pre-multi-model format {id, lang, name}. */
    private fun readMeta(text: String): ModelMeta {
        val obj = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        if ("langs" in obj) return json.decodeFromJsonElement(ModelMeta.serializer(), obj)
        fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return ModelMeta(s("id"), ModelType.VITS, listOf(s("lang")), "Piper " + s("name"))
    }

    private fun detectType(dir: File): String {
        val names = dir.walkTopDown().map { it.name }.toSet()
        return when {
            "voice.bin" in names && "tts.json" in names -> ModelType.SUPERTONIC
            "voices.bin" in names -> ModelType.KOKORO
            else -> ModelType.VITS
        }
    }

    private fun locate(dir: File, meta: ModelMeta): InstalledModel? {
        val all = dir.walkTopDown().toList()
        fun file(pred: (File) -> Boolean) = all.firstOrNull { it.isFile && pred(it) }
        fun named(n: String) = file { it.name == n }
        fun onnx(prefix: String) = file { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        val dataDir = all.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }

        val files: Map<String, File?> = when (meta.type) {
            ModelType.SUPERTONIC -> mapOf(
                "durationPredictor" to onnx("duration_predictor"),
                "textEncoder" to onnx("text_encoder"),
                "vectorEstimator" to onnx("vector_estimator"),
                "vocoder" to onnx("vocoder"),
                "ttsJson" to named("tts.json"),
                "unicodeIndexer" to named("unicode_indexer.bin"),
                "voiceStyle" to named("voice.bin"),
            )
            ModelType.KOKORO -> mapOf(
                "model" to file { it.name.endsWith(".onnx") },
                "voices" to named("voices.bin"),
                "tokens" to named("tokens.txt"),
                "dataDir" to dataDir,
                "lexicon-us" to named("lexicon-us-en.txt"),
                "lexicon-gb" to named("lexicon-gb-en.txt"),
            )
            else -> mapOf(
                "model" to file { it.name.endsWith(".onnx") },
                "tokens" to named("tokens.txt"),
                "dataDir" to dataDir,
                "lexicon" to named("lexicon.txt"),
            )
        }
        val required = when (meta.type) {
            ModelType.SUPERTONIC -> files.keys
            ModelType.KOKORO -> setOf("model", "voices", "tokens", "dataDir")
            else -> setOf("model", "tokens")
        }
        if (required.any { files[it] == null }) return null
        @Suppress("UNCHECKED_CAST")
        return InstalledModel(meta, dir, files.filterValues { it != null } as Map<String, File>)
    }

    companion object {
        fun typeRank(type: String) = when (type) {
            ModelType.SUPERTONIC -> 0
            ModelType.KOKORO -> 1
            else -> 2
        }
    }
}
