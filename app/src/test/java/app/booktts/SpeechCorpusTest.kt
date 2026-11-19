package app.booktts

import app.booktts.text.SpeechTextPreparer
import org.junit.Test
import java.io.File

/**
 * Review aid, not an assertion: runs the pure-Kotlin speech rules over the hard-line corpora and writes
 * before/after pairs to build/speech_corpus_out.txt, so changes to the rules can be eyeballed (and diffed).
 */
class SpeechCorpusTest {

    @Test
    fun write_corpus_before_after() {
        val out = StringBuilder()
        for (lang in listOf("ru", "en")) {
            val lines = javaClass.classLoader!!.getResourceAsStream("speech_corpus_$lang.txt")!!
                .bufferedReader(Charsets.UTF_8).readLines()
            out.append("===== $lang =====\n")
            for (line in lines) {
                if (line.isBlank()) continue
                if (line.startsWith("#")) { out.append('\n').append(line).append('\n'); continue }
                val after = SpeechTextPreparer.textRules(line, lang)
                out.append("  ").append(line).append('\n')
                out.append(if (after == line) "  = (unchanged)\n" else "  > $after\n")
            }
            out.append('\n')
        }
        val file = File("build/speech_corpus_out.txt")
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        println("Speech corpus written to ${file.absolutePath}")
    }
}
