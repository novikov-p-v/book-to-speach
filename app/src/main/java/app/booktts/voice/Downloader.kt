package app.booktts.voice

import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads [url] to [dest], verifying SHA-256 when given. [onBytes] receives the running byte count.
 * Used only for the one-time model/dictionary downloads; everything else is offline.
 */
suspend fun downloadVerified(url: String, dest: File, sha256: String, onBytes: suspend (Long) -> Unit) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 20_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    onBytes(done)
                }
            }
        }
        if (sha256.isNotEmpty()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) error("Checksum mismatch, please retry")
        }
    } finally {
        conn.disconnect()
    }
}
