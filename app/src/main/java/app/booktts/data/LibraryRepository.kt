package app.booktts.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import app.booktts.parse.BookParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Book library stored as JSON files in app-private storage. */
class LibraryRepository(private val context: Context) {
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val indexFile = File(dir, "library.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _books = MutableStateFlow(loadIndex())
    val books: StateFlow<List<Book>> = _books

    @Volatile
    private var cachedContent: Pair<String, BookContent>? = null

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "book"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open file")
        val parsed = BookParsers.parse(name, bytes) { Xml.newPullParser() }
        val built = BookBuilder.build(parsed)
        if (built.content.chunks.isEmpty()) {
            error(if (parsed.format == "pdf") "No text found (scanned PDF?)" else "No text found in file")
        }
        val id = UUID.randomUUID().toString()
        val contentFile = File(dir, "$id.json")
        contentFile.writeText(json.encodeToString(BookContent.serializer(), built.content))
        val book = Book(
            id = id,
            title = parsed.title ?: name,
            author = parsed.author.orEmpty(),
            format = parsed.format,
            lang = built.lang,
            totalChunks = built.content.chunks.size,
            addedAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        mutate { it + book }
        cachedContent = id to built.content
        book
    }

    suspend fun loadContent(id: String): BookContent = withContext(Dispatchers.IO) {
        cachedContent?.takeIf { it.first == id }?.let { return@withContext it.second }
        val content = json.decodeFromString(BookContent.serializer(), File(dir, "$id.json").readText())
        cachedContent = id to content
        content
    }

    fun update(id: String, transform: (Book) -> Book) {
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        File(dir, "$id.json").delete()
        if (cachedContent?.first == id) cachedContent = null
    }

    private fun mutate(f: (List<Book>) -> List<Book>) {
        synchronized(lock) {
            _books.update(f)
            val tmp = File(dir, "library.json.tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Book.serializer()), _books.value))
            tmp.renameTo(indexFile)
        }
    }

    private fun loadIndex(): List<Book> = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Book.serializer()), indexFile.readText())
    }.getOrDefault(emptyList())

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
