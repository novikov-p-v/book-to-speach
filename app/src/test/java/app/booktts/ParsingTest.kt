package app.booktts

import app.booktts.data.BookBuilder
import app.booktts.parse.BookParsers
import app.booktts.parse.Fb2Parser
import app.booktts.parse.TxtParser
import app.booktts.text.LanguageDetector
import app.booktts.text.SentenceSplitter
import app.booktts.text.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParsingTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun fb2_cp1251_chapters_metadata_and_notes() {
        val book = Fb2Parser { KXmlParser() }.parse(resource("sample1251.fb2"))
        assertEquals("Тестовая книга", book.title)
        assertEquals("Лев Толстой", book.author)
        assertEquals("ru", book.lang)
        val titles = book.chapters.map { it.title }
        assertEquals(listOf(null, "Глава 1. Начало", "Глава 2"), titles)
        val all = book.chapters.flatMap { it.paragraphs }.joinToString("\n") { it.text }
        assertTrue(all.contains("Первый абзац текста. Второе предложение."))
        assertTrue(all.contains("Текст второй главы."))
        assertTrue(all.contains("Строка стиха"))
        assertFalse("notes body must be skipped", all.contains("Примечание"))
        assertFalse("note refs must be skipped", all.contains("[1]"))
        // poem title doesn't start a new chapter
        assertEquals(3, book.chapters.size)
    }

    @Test
    fun fb2_zip_is_unpacked() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("book.fb2"))
            z.write(resource("sample1251.fb2"))
            z.closeEntry()
        }
        val book = BookParsers.parse("book.fb2.zip", bos.toByteArray()) { KXmlParser() }
        assertEquals("fb2", book.format)
        assertEquals("Тестовая книга", book.title)
    }

    @Test
    fun txt_cp1251_line_mode_with_headings() {
        val text = "Глава 1\nПервая строка книги. Ещё одно предложение.\nВторой абзац.\nГлава 2\nТекст второй главы.\n"
        val book = TxtParser.parse(text.toByteArray(charset("windows-1251")), "t")
        assertEquals(2, book.chapters.size)
        assertEquals("Глава 1", book.chapters[0].title)
        assertEquals(3, book.chapters[0].paragraphs.size) // heading + 2 paragraphs
        assertTrue(book.chapters[0].paragraphs[0].heading)
    }

    @Test
    fun txt_utf8_block_mode_joins_wrapped_lines() {
        val text = "CHAPTER I\n\nIt was a bright cold day in April, and the clocks\nwere striking thir-\nteen.\n\nSecond paragraph.\n"
        val book = TxtParser.parse(text.toByteArray(), "t")
        val paras = book.chapters.flatMap { it.paragraphs }.map { it.text }
        assertEquals(
            listOf("CHAPTER I", "It was a bright cold day in April, and the clocks were striking thirteen.", "Second paragraph."),
            paras,
        )
    }

    @Test
    fun splitter_merges_short_and_breaks_long() {
        val p = "Да. Нет. Это довольно длинное предложение, в котором есть запятые, тире — и всё такое прочее. Конец."
        val chunks = SentenceSplitter.split(p, Locale.forLanguageTag("ru"), min = 20, max = 60)
        assertTrue(chunks.all { it.length <= 60 })
        assertTrue(chunks.first().startsWith("Да. Нет."))
        assertEquals(p.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun normalizer_drops_decorations() {
        assertEquals("", TextNormalizer.normalize("* * *"))
        assertEquals("Слово и текст", TextNormalizer.normalize("Сло­во и  текст[12]"))
    }

    @Test
    fun language_detection() {
        assertEquals("ru", LanguageDetector.detect("Это русский текст, который нужно прочитать."))
        assertEquals("uk", LanguageDetector.detect("Це український текст, який їм потрібно прочитати і зрозуміти."))
        assertEquals("en", LanguageDetector.detect("This is the text that he was reading with his friends."))
        assertEquals("de", LanguageDetector.detect("Das ist nicht der Text, und die Katze ist mit ihr auf dem Dach."))
    }

    @Test
    fun builder_creates_chunks_and_parts() {
        val long = (1..2000).joinToString("\n") { "Предложение номер $it, достаточно длинное для отдельного фрагмента текста." }
        val built = BookBuilder.build(TxtParser.parse(long.toByteArray(), "t"))
        assertEquals("ru", built.lang)
        assertTrue(built.content.chapters.size > 1)
        assertEquals(0, built.content.chapters[0].start)
        // chapter indexes are consistent with chapter starts
        built.content.chapters.forEachIndexed { i, ch -> assertEquals(i, built.content.chunks[ch.start].chapter) }
        val paragraphs = built.content.paragraphs()
        assertEquals(built.content.chunks.size, paragraphs.sumOf { it.count() })
    }
}
