package app.booktts

import app.booktts.text.Abbreviations
import app.booktts.text.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AbbreviationsTest {

    /** Sentences only: no merging of short ones. */
    private fun sentences(text: String, lang: String) =
        SentenceSplitter.split(text, Locale.forLanguageTag(lang), min = 1, max = 1000)

    @Test
    fun russian_initials_and_abbreviations_do_not_split() {
        assertEquals(
            listOf("Это написал А. С. Пушкин в Михайловском.", "Потом он уехал."),
            sentences("Это написал А. С. Пушкин в Михайловском. Потом он уехал.", "ru"),
        )
        assertEquals(
            listOf("Он жил на ул. Ленина, т. е. в центре.", "Там было тихо."),
            sentences("Он жил на ул. Ленина, т. е. в центре. Там было тихо.", "ru"),
        )
        assertEquals(
            listOf("Подробнее см. Приложение 2 и стр. 15 в т. 2."),
            sentences("Подробнее см. Приложение 2 и стр. 15 в т. 2.", "ru"),
        )
        assertEquals(
            listOf("Т.е. Москва была рядом."),
            sentences("Т.е. Москва была рядом.", "ru"),
        )
        assertEquals(
            listOf("Театр им. Горького открылся в 1932 г. весной."),
            sentences("Театр им. Горького открылся в 1932 г. весной.", "ru"),
        )
    }

    @Test
    fun ambiguous_abbreviation_still_ends_a_sentence() {
        assertEquals(
            listOf("Там были яблоки, груши и т. д.", "Мы ели всё."),
            sentences("Там были яблоки, груши и т. д. Мы ели всё.", "ru"),
        )
        assertEquals(
            listOf("Это случилось в 1812 г.", "Наполеон отступил."),
            sentences("Это случилось в 1812 г. Наполеон отступил.", "ru"),
        )
        assertEquals(
            listOf("We bought apples, pears, etc.", "Then we left."),
            sentences("We bought apples, pears, etc. Then we left.", "en"),
        )
    }

    @Test
    fun english_titles_initials_and_references() {
        assertEquals(
            listOf("Mr. Smith met Dr. Watson and J.R.R. Tolkien.", "They talked."),
            sentences("Mr. Smith met Dr. Watson and J.R.R. Tolkien. They talked.", "en"),
        )
        assertEquals(
            listOf("See p. 5 and No. 7, e.g. The Hobbit.", "Done."),
            sentences("See p. 5 and No. 7, e.g. The Hobbit. Done.", "en"),
        )
        // "no." without a number is a normal word
        assertEquals(
            listOf("She said no.", "He left."),
            sentences("She said no. He left.", "en"),
        )
    }

    @Test
    fun plain_sentences_are_unchanged() {
        val p = "Он пришёл. Она ушла! Кто остался? Никто."
        assertEquals(listOf("Он пришёл.", "Она ушла!", "Кто остался?", "Никто."), sentences(p, "ru"))
        assertEquals(p, Abbreviations.restore(Abbreviations.protect(p)))
    }

    @Test
    fun russian_speech_expansion() {
        assertEquals("Он жил в центре, то есть рядом.", Abbreviations.expand("Он жил в центре, т. е. рядом.", "ru"))
        assertEquals("То есть всё.", Abbreviations.expand("Т.е. всё.", "ru"))
        assertEquals("Яблоки, груши и так далее.", Abbreviations.expand("Яблоки, груши и т. д.", "ru"))
        assertEquals("Груши и так далее. Мы ели.", Abbreviations.expand("Груши и т. д. Мы ели.", "ru"))
        assertEquals("Груши и так далее, а также сливы.", Abbreviations.expand("Груши и т. д., а также сливы.", "ru"))
        assertEquals("Например, смотри рисунок.", Abbreviations.expand("Напр., см. рисунок.", "ru"))
        // centimetres stay as they are
        assertEquals("Длина 5 см. Ширина 3 см.", Abbreviations.expand("Длина 5 см. Ширина 3 см.", "ru"))
        assertEquals("Все, в том числе дети.", Abbreviations.expand("Все, в т. ч. дети.", "ru"))
    }

    @Test
    fun english_speech_expansion() {
        assertEquals("Fruit, for example apples.", Abbreviations.expand("Fruit, e.g. apples.", "en"))
        assertEquals("Apples, pears, et cetera.", Abbreviations.expand("Apples, pears, etc.", "en"))
        assertEquals("Mister Smith and Doctor Watson.", Abbreviations.expand("Mr. Smith and Dr. Watson.", "en"))
        // other languages are untouched
        assertEquals("Mr. Smith, т. е. он.", Abbreviations.expand("Mr. Smith, т. е. он.", "de"))
    }
}
