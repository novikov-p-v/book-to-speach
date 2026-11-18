package app.booktts.data

import app.booktts.parse.ParsedBook
import app.booktts.text.LanguageDetector
import app.booktts.text.SentenceSplitter
import app.booktts.text.TextNormalizer
import java.util.Locale

/** Turns a [ParsedBook] into speakable chunks grouped by chapter. */
object BookBuilder {
    /** Books without detectable chapters are cut into parts of about this many chunks (~15-20 min). */
    const val AUTO_PART_CHUNKS = 250

    data class Result(val lang: String, val content: BookContent)

    fun build(parsed: ParsedBook): Result {
        val lang = parsed.lang ?: LanguageDetector.detect(sampleText(parsed))
        val locale = Locale.forLanguageTag(lang)

        val chunks = ArrayList<Chunk>()
        val chapters = ArrayList<ChapterInfo>()
        for (ch in parsed.chapters) {
            val chapterIdx = chapters.size
            val start = chunks.size
            for (p in ch.paragraphs) {
                val text = TextNormalizer.normalize(p.text)
                if (text.isEmpty()) continue
                val parts = if (p.heading) listOf(text) else SentenceSplitter.split(text, locale)
                parts.forEachIndexed { i, s ->
                    chunks += Chunk(s, chapterIdx, paragraphEnd = i == parts.lastIndex, heading = p.heading)
                }
            }
            if (chunks.size > start) {
                val title = ch.title?.let(TextNormalizer::normalize)?.takeIf { it.isNotEmpty() }
                    ?: if (chapterIdx == 0) parsed.title ?: "1" else "${chapterIdx + 1}"
                chapters += ChapterInfo(title, start)
            }
        }

        if (chapters.size == 1 && chunks.size > AUTO_PART_CHUNKS * 3 / 2) {
            return Result(lang, splitIntoParts(chunks))
        }
        return Result(lang, BookContent(chapters, chunks))
    }

    private fun splitIntoParts(chunks: List<Chunk>): BookContent {
        val out = ArrayList<Chunk>(chunks.size)
        val chapters = ArrayList<ChapterInfo>()
        var part = 0
        var inPart = 0
        for ((i, c) in chunks.withIndex()) {
            if (i == 0) chapters += ChapterInfo("Part 1", 0)
            out += c.copy(chapter = part)
            inPart++
            // cut only at paragraph boundaries
            if (inPart >= AUTO_PART_CHUNKS && c.paragraphEnd && i < chunks.lastIndex) {
                part++
                inPart = 0
                chapters += ChapterInfo("Part ${part + 1}", i + 1)
            }
        }
        return BookContent(chapters, out)
    }

    private fun sampleText(parsed: ParsedBook): String {
        val sb = StringBuilder()
        loop@ for (ch in parsed.chapters) for (p in ch.paragraphs) {
            sb.append(p.text).append(' ')
            if (sb.length > 8000) break@loop
        }
        return sb.toString()
    }
}
