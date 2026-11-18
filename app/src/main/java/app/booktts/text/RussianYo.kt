package app.booktts.text

import java.io.File
import java.io.RandomAccessFile
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Restores the letter ё in Russian text (еще -> ещё, шел -> шёл, ее -> её) using the RUAccent
 * ё dictionary (MIT, bundled in assets/ru). Books are usually printed with "е" instead of "ё",
 * which makes espeak-based voices mispronounce those words; with ё they are read correctly.
 * Ambiguous words (все/всё, небо/нёбо) are left unchanged.
 */
class RussianYo private constructor(private val yo: DictFile) {

    fun process(text: String): String = WORD.replace(text) { m -> processWord(m.value) }

    private fun processWord(word: String): String {
        val lower = word.lowercase()
        if ('ё' in lower) return word
        val idx = yo.lookup(lower)
        if (idx == null) {
            // hyphenated words not in the dictionary: handle each part ("пошел-таки")
            return if ('-' in word) word.split('-').joinToString("-") { processWord(it) } else word
        }
        val chars = word.toCharArray()
        for (b in idx) {
            val i = b.toInt()
            if (i in chars.indices) chars[i] = if (chars[i].isUpperCase()) 'Ё' else 'ё'
        }
        return String(chars)
    }

    companion object {
        private val WORD = Regex("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*")
        const val YO_FILE = "yo.bin"

        fun open(file: File) = RussianYo(DictFile.open(file))

        /** Compiles RUAccent's yo_words / yo_homographs JSON into a compact [DictFile]. */
        fun build(yoWords: () -> Reader, yoHomographs: () -> Reader, out: File) {
            val ambiguous = HashSet<String>()
            yoHomographs().use { r -> FlatJson.forEach(r) { k, _ -> ambiguous += k } }
            DictBuilder.build(out, bucketCount = 1 shl 15) { emit ->
                yoWords().use { r ->
                    FlatJson.forEach(r) { k, v ->
                        if (v == null || k in ambiguous || v.length != k.length) return@forEach
                        val idx = ByteArray(k.length)
                        var n = 0
                        for (i in k.indices) if (k[i] == 'е' && v[i] == 'ё') idx[n++] = i.toByte()
                        if (n > 0) emit(k, idx.copyOf(n))
                    }
                }
            }
        }
    }
}

/**
 * Read-only on-disk hash table: word -> small byte value. Memory-mapped, so lookups cost a few
 * reads and the dictionary needs almost no heap.
 *
 * Layout: "RSD1", bucketCount, (bucketCount + 1) data offsets, entries [keyLen][key][valLen][val].
 * Keys use a 1-byte Cyrillic encoding (see [WordCodec]).
 */
class DictFile private constructor(private val buf: ByteBuffer, private val buckets: Int, private val dataStart: Int) {

    fun lookup(word: String): ByteArray? {
        val key = WordCodec.encode(word) ?: return null
        val b = WordCodec.bucket(key, buckets)
        var p = dataStart + buf.getInt(HEADER + b * 4)
        val end = dataStart + buf.getInt(HEADER + (b + 1) * 4)
        while (p < end) {
            val kl = buf.get(p).toInt() and 0xFF
            var match = kl == key.size
            if (match) {
                for (i in 0 until kl) if (buf.get(p + 1 + i) != key[i]) { match = false; break }
            }
            val vp = p + 1 + kl
            val vl = buf.get(vp).toInt() and 0xFF
            if (match) return ByteArray(vl) { buf.get(vp + 1 + it) }
            p = vp + 1 + vl
        }
        return null
    }

    companion object {
        private const val HEADER = 8

        fun open(file: File): DictFile {
            val buf = RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            check(buf.getInt(0) == DictBuilder.MAGIC) { "Not a dictionary file: $file" }
            val buckets = buf.getInt(4)
            return DictFile(buf, buckets, HEADER + (buckets + 1) * 4)
        }
    }
}

/** Builds a [DictFile] in two streaming passes (sizes per bucket, then placement); no sorting needed. */
object DictBuilder {
    const val MAGIC = 0x52534431 // "RSD1"

    fun build(out: File, bucketCount: Int, source: ((emit: (String, ByteArray) -> Unit) -> Unit)) {
        // pass 1: bytes per bucket
        val sizes = LongArray(bucketCount + 1)
        source { k, v ->
            val key = WordCodec.encode(k) ?: return@source
            if (key.size > 255 || v.size > 255) return@source
            sizes[WordCodec.bucket(key, bucketCount)] += (2 + key.size + v.size).toLong()
        }
        val offsets = IntArray(bucketCount + 1)
        var total = 0L
        for (b in 0 until bucketCount) {
            offsets[b] = total.toInt()
            total += sizes[b]
        }
        offsets[bucketCount] = total.toInt()
        val dataStart = 8 + (bucketCount + 1) * 4
        val cursor = offsets.copyOf()

        val tmp = File(out.path + ".tmp")
        tmp.delete()
        RandomAccessFile(tmp, "rw").use { raf ->
            val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, dataStart + total)
            map.putInt(0, MAGIC)
            map.putInt(4, bucketCount)
            for (i in offsets.indices) map.putInt(8 + i * 4, offsets[i])
            // pass 2: place entries
            source { k, v ->
                val key = WordCodec.encode(k) ?: return@source
                if (key.size > 255 || v.size > 255) return@source
                val b = WordCodec.bucket(key, bucketCount)
                var p = dataStart + cursor[b]
                map.put(p++, key.size.toByte())
                for (x in key) map.put(p++, x)
                map.put(p++, v.size.toByte())
                for (x in v) map.put(p++, x)
                cursor[b] = p - dataStart
            }
            map.force()
        }
        out.delete()
        check(tmp.renameTo(out)) { "Cannot write $out" }
    }
}

/** 1-byte encoding of lowercase Russian words; anything else is not stored. */
object WordCodec {
    fun encode(word: String): ByteArray? {
        val out = ByteArray(word.length)
        for (i in word.indices) {
            val c = word[i]
            out[i] = when (c) {
                in 'а'..'я' -> (c - 'а' + 1).toByte()
                'ё' -> 33
                '-' -> 34
                else -> return null
            }
        }
        return out
    }

    /** FNV-1a */
    fun bucket(key: ByteArray, buckets: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (b in key) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return (h and 0x7FFFFFFF) % buckets
    }
}

/**
 * Minimal streaming reader for flat JSON objects: {"key": "value" | ["v1", ...], ...}.
 * Calls [action] with the key and the string value (null for arrays). Avoids loading
 * the 3-million-entry dictionary into memory.
 */
object FlatJson {
    fun forEach(reader: Reader, action: (String, String?) -> Unit) {
        val r = if (reader is java.io.BufferedReader) reader else reader.buffered(1 shl 16)
        val sb = StringBuilder()

        fun next(): Int {
            while (true) {
                val c = r.read()
                if (c < 0 || !Character.isWhitespace(c)) return c
            }
        }

        fun readString(): String {
            sb.setLength(0)
            while (true) {
                val c = r.read()
                require(c >= 0) { "Unexpected end of JSON" }
                when (c.toChar()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = r.read().toChar()) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> sb.append(CharArray(4) { r.read().toChar() }.concatToString().toInt(16).toChar())
                        else -> sb.append(e)
                    }
                    else -> sb.append(c.toChar())
                }
            }
        }

        require(next() == '{'.code) { "Expected JSON object" }
        var c = next()
        if (c == '}'.code) return
        while (true) {
            require(c == '"'.code) { "Expected key" }
            val key = readString()
            require(next() == ':'.code) { "Expected ':'" }
            val value: String? = when (next()) {
                '"'.code -> readString()
                '['.code -> {
                    var d = next()
                    while (d != ']'.code) {
                        if (d == '"'.code) readString()
                        d = next()
                        if (d == ','.code) d = next()
                    }
                    null
                }
                else -> error("Unsupported JSON value for key $key")
            }
            action(key, value)
            c = next()
            if (c == '}'.code) return
            require(c == ','.code) { "Expected ',' or '}'" }
            c = next()
        }
    }
}
