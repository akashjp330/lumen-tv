package lumen.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Disk cache in front of another fetcher. With [cacheFirst] = true a cached copy is
 * returned without touching the network, so the app opens instantly; a later
 * refresh uses cacheFirst = false, which downloads and replaces the copy.
 *
 * Bodies are stored gzipped (guide files shrink ~10x). A copy is only committed
 * when the download was read to the end, so a dropped connection never leaves a
 * truncated cache behind.
 */
class CachingFetcher(
    private val dir: File,
    private val network: HttpFetcher,
    private val cacheFirst: Boolean,
) : HttpFetcher {

    init { dir.mkdirs() }

    fun cacheFile(url: String) = File(dir, M3uParser.stableKey(url) + ".gz")

    fun cachedAgeMs(url: String, nowMs: Long): Long? =
        cacheFile(url).takeIf { it.exists() }?.let { nowMs - it.lastModified() }

    override fun open(url: String): InputStream {
        val file = cacheFile(url)
        if (cacheFirst && file.exists() && file.length() > 0) {
            return GZIPInputStream(file.inputStream().buffered(64 * 1024), 64 * 1024)
        }
        val src = network.open(url)
        val tmp = File(dir, file.name + ".part")
        val out = GZIPOutputStream(tmp.outputStream().buffered(64 * 1024), 64 * 1024)
        return object : FilterInputStream(src) {
            private var done = false
            private var closed = false

            override fun read(): Int {
                val b = super.read()
                if (b < 0) done = true else out.write(b)
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n < 0) done = true else out.write(b, off, n)
                return n
            }

            override fun close() {
                if (closed) return
                closed = true
                try { super.close() } finally {
                    runCatching { out.close() }
                    if (done) {
                        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                    } else tmp.delete()
                }
            }
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
