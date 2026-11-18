package app.booktts.parse

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtParser {

    private val headingRegex = Regex(
        """^\s*(глава|chapter|часть|part|книга|book|пролог|эпилог|prologue|epilogue|розділ|kapitel|chapitre|capítulo|capitolo|rozdział)(?!\p{L}).{0,80}$""",
        RegexOption.IGNORE_CASE,
    )
    private val numberHeadingRegex = Regex("""^\s*([IVXLC]{1,7}|\d{1,3})\.?\s*$""")

    fun decode(bytes: ByteArray): String {
        // BOMs
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // Strict UTF-8 is a very reliable signal.
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }

        val detector = UniversalDetector()
        detector.handleData(bytes, 0, minOf(bytes.size, 512 * 1024))
        detector.dataEnd()
        val detected = detector.detectedCharset
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charset.forName("windows-1251")
        return String(bytes, detected)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    fun parse(bytes: ByteArray, title: String?): ParsedBook {
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n').trim()
        val lines = text.split('\n')
        val nonEmpty = lines.count { it.isNotBlank() }
        val blank = lines.size - nonEmpty

        // Block mode: paragraphs are separated by blank lines (hard-wrapped text).
        // Line mode: each non-empty line is a paragraph (typical for Russian .txt books).
        val blockMode = nonEmpty > 0 && blank > nonEmpty * 0.1
        val paragraphs = ArrayList<String>()
        if (blockMode) {
            val sb = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) {
                    if (sb.isNotEmpty()) { paragraphs += sb.toString(); sb.clear() }
                } else {
                    val l = line.trim()
                    if (sb.isNotEmpty()) {
                        if (sb.endsWith("-") && l.firstOrNull()?.isLowerCase() == true) sb.setLength(sb.length - 1)
                        else sb.append(' ')
                    }
                    sb.append(l)
                }
            }
            if (sb.isNotEmpty()) paragraphs += sb.toString()
        } else {
            lines.filter { it.isNotBlank() }.mapTo(paragraphs) { it.trim() }
        }

        val chapters = ArrayList<ParsedChapter>()
        var currentTitle: String? = null
        var current = ArrayList<Para>()
        for (p in paragraphs) {
            if (isHeading(p)) {
                if (current.isNotEmpty()) chapters += ParsedChapter(currentTitle, current)
                currentTitle = p
                current = arrayListOf(Para(p, heading = true))
            } else {
                current += Para(p)
            }
        }
        if (current.isNotEmpty()) chapters += ParsedChapter(currentTitle, current)

        return ParsedBook(title = title, author = null, lang = null, format = "txt", chapters = chapters)
    }

    internal fun isHeading(p: String): Boolean =
        p.length <= 90 && (headingRegex.matches(p) || numberHeadingRegex.matches(p))
}
