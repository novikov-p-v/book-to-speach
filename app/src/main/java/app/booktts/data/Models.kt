package app.booktts.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    /** ISO 639-1 language code used to pick the default voice. */
    val lang: String,
    /** Explicit voice for this book; null = default voice for [lang]. */
    val voiceId: String? = null,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long = 0,
) {
    val progress: Float get() = if (totalChunks <= 1) 0f else chunkIndex.toFloat() / (totalChunks - 1)
}

/** One unit of synthesis: roughly a sentence. */
@Serializable
data class Chunk(
    @SerialName("t") val text: String,
    @SerialName("c") val chapter: Int,
    /** last chunk of a paragraph (longer pause after it) */
    @SerialName("p") val paragraphEnd: Boolean = false,
    @SerialName("h") val heading: Boolean = false,
)

@Serializable
data class ChapterInfo(val title: String, val start: Int)

@Serializable
data class BookContent(val chapters: List<ChapterInfo>, val chunks: List<Chunk>) {
    fun chapterOf(chunkIndex: Int): Int = chunks.getOrNull(chunkIndex)?.chapter ?: 0

    /** chunk ranges of chapter [i] */
    fun chapterRange(i: Int): IntRange {
        val start = chapters[i].start
        val end = if (i + 1 < chapters.size) chapters[i + 1].start else chunks.size
        return start until end
    }

    /** Paragraphs as ranges of chunk indices (for rendering). */
    fun paragraphs(): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        chunks.forEachIndexed { i, c ->
            val nextStartsChapter = i + 1 < chunks.size && chunks[i + 1].chapter != c.chapter
            if (c.paragraphEnd || nextStartsChapter || i == chunks.lastIndex) {
                out += start..i
                start = i + 1
            }
        }
        return out
    }
}
