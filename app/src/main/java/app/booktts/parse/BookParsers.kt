package app.booktts.parse

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object BookParsers {

    val supportedExtensions = listOf("txt", "fb2", "pdf", "zip")

    /**
     * @param fileName display name, used for the format and as fallback title.
     * @param xmlParser factory for an XmlPullParser (Xml.newPullParser on Android).
     */
    fun parse(fileName: String, bytes: ByteArray, xmlParser: () -> XmlPullParser): ParsedBook {
        val lower = fileName.lowercase()
        val baseTitle = fileName.substringBeforeLast('.').removeSuffix(".fb2").removeSuffix(".txt")

        if (isZip(bytes)) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && (name.endsWith(".fb2") || name.endsWith(".txt") || name.endsWith(".pdf"))) {
                        return parse(entry.name.substringAfterLast('/'), zip.readBytes(), xmlParser)
                    }
                }
            }
            throw UnsupportedFormatException("Archive contains no .fb2, .txt or .pdf file")
        }

        return when {
            isPdf(bytes) || lower.endsWith(".pdf") -> PdfParser.parse(bytes, baseTitle)
            lower.endsWith(".fb2") || looksLikeFb2(bytes) -> Fb2Parser(xmlParser).parse(bytes).let {
                if (it.title == null) it.copy(title = baseTitle) else it
            }
            else -> TxtParser.parse(bytes, baseTitle)
        }
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
    private fun isPdf(b: ByteArray) = b.size > 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "%PDF"
    private fun looksLikeFb2(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1)
        return head.contains("<FictionBook")
    }
}

class UnsupportedFormatException(message: String) : Exception(message)
