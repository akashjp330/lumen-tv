package lumen.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Abstracted so tests (and the app) can swap the transport. */
interface HttpFetcher {
    /** Opens the body of a GET. Caller closes. Transparently un-gzips. */
    fun open(url: String): InputStream

    fun getText(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }
}

class HttpException(val code: Int, url: String) :
    IOException("HTTP $code from ${LogScrubber.scrubUrl(url)}")

class UrlConnectionFetcher(
    private val userAgent: String = "LumenTV/1.0",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpFetcher {

    override fun open(url: String): InputStream {
        var current = url
        // HttpURLConnection won't follow http<->https redirects on its own, and IPTV
        // panels redirect across protocols and hosts all the time.
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw HttpException(code, current)
                conn.disconnect()
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw HttpException(code, current)
            }
            return maybeGunzip(conn.inputStream)
        }
        throw IOException("Too many redirects")
    }

    companion object {
        /** Detects gzip by magic bytes, so .xml.gz files and gzip-encoded responses both work. */
        fun maybeGunzip(raw: InputStream): InputStream {
            val buffered = BufferedInputStream(raw, 64 * 1024)
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        }
    }
}
