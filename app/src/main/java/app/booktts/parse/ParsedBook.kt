package app.booktts.parse

/** A paragraph of book text. [heading] marks chapter/section titles. */
data class Para(val text: String, val heading: Boolean = false)

data class ParsedChapter(val title: String?, val paragraphs: List<Para>)

/** Format-independent result of parsing a book file. */
data class ParsedBook(
    val title: String?,
    val author: String?,
    /** ISO 639-1 code if the file declares it (FB2 does), else null. */
    val lang: String?,
    val format: String,
    val chapters: List<ParsedChapter>,
)
