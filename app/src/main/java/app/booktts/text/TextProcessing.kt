package app.booktts.text

import java.text.BreakIterator
import java.util.Locale

object TextNormalizer {
    private val invisible = Regex("[­​‌‍⁠﻿]")
    private val spaces = Regex("[\\s   ]+")
    private val footnoteRefs = Regex("""\[\d{1,4}\]|\{\d{1,4}\}""")

    /** Returns cleaned text, or "" if nothing speakable is left (e.g. "* * *"). */
    fun normalize(s: String): String {
        val t = s.replace(invisible, "")
            .replace(footnoteRefs, "")
            .replace(spaces, " ")
            .trim()
        return if (t.any { it.isLetterOrDigit() }) t else ""
    }
}

object SentenceSplitter {
    const val MIN_CHUNK = 60
    const val MAX_CHUNK = 280

    /**
     * Splits a paragraph into speakable chunks: sentences, with very short ones merged
     * and very long ones broken at punctuation / spaces.
     */
    fun split(paragraph: String, locale: Locale, min: Int = MIN_CHUNK, max: Int = MAX_CHUNK): List<String> {
        val bi = BreakIterator.getSentenceInstance(locale)
        bi.setText(paragraph)
        val sentences = ArrayList<String>()
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val s = paragraph.substring(start, end).trim()
            if (s.isNotEmpty()) sentences += s
            start = end
            end = bi.next()
        }
        val pieces = sentences.flatMap { splitLong(it, max) }

        val out = ArrayList<String>()
        val acc = StringBuilder()
        for (p in pieces) {
            if (acc.isEmpty()) {
                acc.append(p)
            } else if (acc.length < min && acc.length + 1 + p.length <= max) {
                acc.append(' ').append(p)
            } else {
                out += acc.toString()
                acc.clear().append(p)
            }
        }
        if (acc.isNotEmpty()) {
            // glue a tiny tail onto the previous chunk if it fits
            if (out.isNotEmpty() && acc.length < min / 3 && out.last().length + 1 + acc.length <= max) {
                out[out.lastIndex] = out.last() + " " + acc
            } else {
                out += acc.toString()
            }
        }
        return out
    }

    private val softBreaks = charArrayOf(';', ':', ',', '—', '–', ')')

    internal fun splitLong(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = softBreaks.maxOf { window.lastIndexOf(it) }
            if (cut < max / 3) cut = window.lastIndexOf(' ')
            if (cut < max / 3) cut = max - 1
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

object LanguageDetector {
    private val stopwords = mapOf(
        "en" to setOf("the", "and", "of", "to", "a", "in", "is", "that", "it", "was", "he", "for", "with", "his", "you"),
        "de" to setOf("der", "die", "und", "das", "ist", "nicht", "ich", "zu", "den", "mit", "sie", "ein", "es", "auf"),
        "fr" to setOf("le", "la", "les", "et", "des", "est", "un", "une", "que", "il", "pas", "dans", "du", "je"),
        "es" to setOf("el", "la", "de", "que", "y", "los", "se", "en", "un", "por", "con", "las", "del", "una"),
        "it" to setOf("il", "di", "che", "e", "la", "per", "non", "un", "una", "sono", "del", "le", "gli", "della"),
        "pl" to setOf("i", "w", "nie", "na", "się", "z", "jest", "to", "że", "do", "jak", "ale", "co", "tak"),
    )

    fun detect(sample: String): String {
        var cyr = 0
        var lat = 0
        var ukr = 0
        for (c in sample) {
            when {
                c in 'Ѐ'..'ӿ' -> {
                    cyr++
                    if (c in "іїєґІЇЄҐ") ukr++
                }
                c in 'a'..'z' || c in 'A'..'Z' || c in 'À'..'ſ' -> lat++
            }
        }
        if (cyr == 0 && lat == 0) return "en"
        if (cyr >= lat) return if (ukr.toDouble() / cyr > 0.01) "uk" else "ru"

        val words = sample.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        val scores = stopwords.mapValues { (_, set) -> words.count { it in set } }
        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: "en"
    }
}
