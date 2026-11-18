package app.booktts.text

/**
 * Abbreviations and initials:
 *  - [protect] hides periods that do not end a sentence, so the sentence splitter does not cut there
 *    ("А. С. Пушкин", "Mr. Smith", "т. е. Москва", "p. 5");
 *  - [expand] spells common abbreviations out for speech ("т. е." -> "то есть", "e.g." -> "for example").
 */
object Abbreviations {
    /** Stands in for a protected period; a private-use char, so BreakIterator never treats it as a sentence end. */
    const val DOT = ''

    /** Never end a sentence: always protected. Russian ones match any case ("См. рис." at a sentence start). */
    private val NEVER_RU = listOf(
        "т. е.", "т. к.", "т. н.", "т. ч.", "т. о.", "напр.", "см.", "ср.", "ул.", "пер.", "просп.", "пл.", "обл.",
        "им.", "св.", "проф.", "акад.", "доц.", "ген.", "полк.", "тов.", "гр.", "англ.", "лат.", "греч.", "франц.", "нем.",
    )
    private val NEVER_OTHER = listOf(
        "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "Rev.", "Gen.", "Col.", "Capt.", "Lt.", "Sgt.", "Mt.", "Messrs.",
        "e.g.", "i.e.", "vs.", "cf.", "approx.", "viz.",
        "z. B.", "d. h.", "u. a.", "bzw.", "ggf.", "Hr.", "Fr.",
        "Mme.", "Mlle.", // French; "M. Dupont" is covered by the initials rule
    )

    /** Only before a number: "p. 5", "No. 3", "стр. 12", "т. 2", "гл. 4". */
    private val BEFORE_NUMBER = listOf(
        "No.", "Nos.", "no.", "Vol.", "vol.", "p.", "pp.", "ch.", "Ch.", "Fig.", "fig.", "Nr.", "Art.", "art.",
        "стр.", "с.", "т.", "гл.", "рис.", "табл.", "д.", "кв.", "ст.", "п.", "ч.",
    )

    /** Can end a sentence: protected unless the next word starts with a capital letter. */
    private val AMBIGUOUS = listOf(
        "и т. д.", "и т. п.", "и др.", "и пр.", "г.", "гг.", "в.", "вв.", "др.", "руб.", "коп.", "тыс.", "млн.", "млрд.",
        "н. э.", "до н. э.", "etc.", "Jr.", "Sr.", "Inc.", "Ltd.", "Co.", "St.", "Ave.", "usw.",
    )

    /** "т. е." -> regex matching "т. е." and "т.е.", not inside a longer word. */
    private fun pattern(abbr: String): String {
        val a = abbr.replace(". ", ".")
        val body = StringBuilder()
        for ((i, c) in a.withIndex()) {
            when {
                c == '.' && i < a.lastIndex -> body.append("\\.\\s?")
                c == '.' -> body.append("\\.")
                c == ' ' -> body.append("\\s+")
                c.isLetter() -> body.append(c)
                else -> body.append('\\').append(c)
            }
        }
        return "(?<![\\p{L}\\p{N}])$body"
    }

    private fun alternation(list: List<String>) =
        list.sortedByDescending { it.length }.joinToString("|") { "(?:${pattern(it)})" }

    private val neverRu = Regex(alternation(NEVER_RU), RegexOption.IGNORE_CASE)
    private val neverOther = Regex(alternation(NEVER_OTHER))
    private val beforeNumber = Regex("(?:${alternation(BEFORE_NUMBER)})(?=\\s?\\d)")
    private val ambiguous = Regex("(?:${alternation(AMBIGUOUS)})(?!\\s*[\"«“(]?\\p{Lu})")

    /** Single-letter initials followed by a capital: "А. С. Пушкин", "J.R.R. Tolkien". */
    private val initials = Regex("(?<![\\p{L}\\p{N}])\\p{Lu}\\.(?=\\s?\\p{Lu})")

    fun protect(text: String): String {
        if ('.' !in text) return text
        var t = text
        for (re in listOf(neverRu, neverOther, beforeNumber, ambiguous, initials)) {
            t = re.replace(t) { it.value.replace('.', DOT) }
        }
        return t
    }

    fun restore(text: String): String = text.replace(DOT, '.')

    // --- speech ---

    /** [canEnd]: the abbreviation may end a sentence ("и т. д.", "etc."), so its period may be the full stop. */
    private class Expansion(val re: Regex, val spoken: String, val canEnd: Boolean)

    private fun expansions(vararg pairs: Pair<String, String>, ignoreCase: Boolean, canEnd: Boolean = false, extra: String = "") =
        pairs.sortedByDescending { it.first.length }.map { (abbr, spoken) ->
            val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Expansion(Regex(pattern(abbr) + extra, opts), spoken, canEnd)
        }

    private val speechRu = expansions(
        "и т. д." to "и так далее",
        "и т. п." to "и тому подобное",
        "и др." to "и другие",
        "и пр." to "и прочее",
        ignoreCase = true, canEnd = true,
    ) + expansions(
        "т. е." to "то есть",
        "т. к." to "так как",
        "в т. ч." to "в том числе",
        "напр." to "например",
        ignoreCase = true,
    ) + expansions(
        // "5 см." is centimetres, not "смотри"
        "см." to "смотри",
        ignoreCase = true, extra = "(?<!\\d\\s?см\\.)",
    )

    private val speechEn = expansions(
        "etc." to "et cetera",
        ignoreCase = false, canEnd = true,
    ) + expansions(
        "e.g." to "for example",
        "i.e." to "that is",
        "vs." to "versus",
        "cf." to "compare",
        "approx." to "approximately",
        ignoreCase = false,
    ) + expansions(
        "Mr." to "Mister",
        "Mrs." to "Missus",
        "Dr." to "Doctor",
        "Prof." to "Professor",
        ignoreCase = false, extra = "(?=\\s?\\p{Lu})",
    )

    private val sentenceStart = Regex("^\\s+[\"«“(—–-]*\\s*\\p{Lu}")

    /** Speaks abbreviations out in [lang]; keeps a sentence-final period where the abbreviation ended the sentence. */
    fun expand(text: String, lang: String): String {
        val list = when (lang) {
            "ru" -> speechRu
            "en" -> speechEn
            else -> return text
        }
        if ('.' !in text) return text
        var t = text
        for (e in list) {
            t = e.re.replace(t) { m ->
                val spoken = if (m.value.first().isUpperCase()) e.spoken.replaceFirstChar { it.uppercaseChar() } else e.spoken
                val rest = t.substring(m.range.last + 1)
                val endsSentence = e.canEnd && (rest.isBlank() || sentenceStart.containsMatchIn(rest))
                if (endsSentence) "$spoken." else spoken
            }
        }
        return t
    }
}
