package app.booktts.parse

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Extracts text from text-based PDFs (scanned PDFs without a text layer yield nothing). */
object PdfParser {

    private const val PARA = " "

    fun parse(bytes: ByteArray, fallbackTitle: String?): ParsedBook {
        PDDocument.load(bytes).use { doc ->
            val pageCount = doc.numberOfPages
            val info = doc.documentInformation

            // Top-level outline entries become chapters (if the PDF has bookmarks).
            val marks = ArrayList<Pair<Int, String>>()
            doc.documentCatalog?.documentOutline?.children()?.forEach { item: PDOutlineItem ->
                val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                val idx = page?.let { doc.pages.indexOf(it) } ?: -1
                val title = item.title?.trim().orEmpty()
                if (idx >= 0 && title.isNotEmpty()) marks += idx to title
            }
            marks.sortBy { it.first }

            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphEnd = PARA
                lineSeparator = "\n"
                pageEnd = PARA
            }

            val ranges: List<Pair<String?, IntRange>> = if (marks.isEmpty()) {
                listOf(null to (1..pageCount))
            } else {
                val list = ArrayList<Pair<String?, IntRange>>()
                if (marks.first().first > 0) list += null to (1..marks.first().first)
                marks.forEachIndexed { i, (page, title) ->
                    val end = if (i + 1 < marks.size) marks[i + 1].first else pageCount
                    if (end > page) list += title to ((page + 1)..end)
                }
                list
            }

            val chapters = ranges.mapNotNull { (title, pages) ->
                stripper.startPage = pages.first
                stripper.endPage = pages.last
                val raw = stripper.getText(doc)
                val paras = toParagraphs(raw)
                if (paras.isEmpty()) null else ParsedChapter(title, paras.map { Para(it) })
            }

            return ParsedBook(
                title = info?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
                author = info?.author?.trim()?.takeIf { it.isNotEmpty() },
                lang = null,
                format = "pdf",
                chapters = chapters,
            )
        }
    }

    /** Joins hard-wrapped lines, removes hyphenation and bare page numbers. */
    internal fun toParagraphs(raw: String): List<String> =
        raw.split(PARA).mapNotNull { block ->
            val sb = StringBuilder()
            for (line in block.split('\n')) {
                val l = line.trim()
                if (l.isEmpty() || l.all { it.isDigit() }) continue
                if (sb.isNotEmpty()) {
                    val last = sb.last()
                    if ((last == '-' || last == '­') && l.first().isLowerCase()) sb.setLength(sb.length - 1)
                    else sb.append(' ')
                }
                sb.append(l)
            }
            sb.toString().takeIf { it.isNotBlank() }
        }
}
