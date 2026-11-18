package app.booktts

import app.booktts.text.FlatJson
import app.booktts.text.RussianYo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import java.util.zip.GZIPInputStream

class RussianYoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun flat_json_reads_strings_arrays_and_escapes() {
        val out = mutableListOf<Pair<String, String?>>()
        FlatJson.forEach(StringReader("""{ "a" : "b\"cё", "d": ["x", "y"], "e":"" }""")) { k, v -> out += k to v }
        assertEquals(listOf("a" to "b\"cё", "d" to null, "e" to ""), out)
    }

    @Test
    fun restores_yo_keeps_case_skips_ambiguous() {
        val file = tmp.newFile("yo.bin")
        RussianYo.build(
            yoWords = { StringReader("""{"еще": "ещё", "ее": "её", "шел": "шёл", "все": "всё", "черно-желтый": "чёрно-жёлтый"}""") },
            yoHomographs = { StringReader("""{"все": "всё"}""") },
            out = file,
        )
        val yo = RussianYo.open(file)
        assertEquals("Ещё раз: он шёл, и мы её не узнали. Все ЕЩЁ", yo.process("Еще раз: он шел, и мы ее не узнали. Все ЕЩЕ"))
        // whole hyphenated entry, and per-part fallback for unknown compounds
        assertEquals("чёрно-жёлтый ещё-то", yo.process("черно-желтый еще-то"))
        assertEquals("Hello мир", yo.process("Hello мир"))
    }

    /** Builds from the bundled asset, i.e. exactly what the app ships. */
    @Test
    fun bundled_dictionary() {
        val assets = listOf(File("src/main/assets/ru"), File("app/src/main/assets/ru")).first { it.isDirectory }
        fun gz(name: String) = { GZIPInputStream(File(assets, name).inputStream()).reader() }
        val file = tmp.newFile("yo.bin")
        val t0 = System.currentTimeMillis()
        RussianYo.build(gz("yo_words.json.gz"), gz("yo_homographs.json.gz"), file)
        println("build: ${System.currentTimeMillis() - t0} ms, ${file.length() / 1000} KB")
        val yo = RussianYo.open(file)
        assertEquals(
            "Ещё раз всё проверим: он еле шёл, и мы её не узнали. Её чёрный котёнок пришёл.",
            yo.process("Еще раз всё проверим: он еле шел, и мы ее не узнали. Ее черный котенок пришел."),
        )
        // ambiguous: все (all) / всё (everything) stays as written
        assertEquals("все", yo.process("все"))
    }
}
