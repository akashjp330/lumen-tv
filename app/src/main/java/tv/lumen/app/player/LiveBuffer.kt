@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.lumen.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import lumen.core.UrlConnectionFetcher
import tv.lumen.app.data.DiagLog
import tv.lumen.app.data.Repository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Live pause & rewind. While you watch, the channel is written to the device in
 * 32 MB pieces and the player reads from them, so you can pause, go back, and
 * catch up. Older pieces are deleted once the buffer passes [maxBytes].
 *
 * It also smooths reconnects: if the provider's stream drops, the writer
 * reconnects on its own while the player keeps playing what's already saved.
 *
 * Uses the same single connection the player would have used; no extra stream.
 */
class LiveBuffer(val url: String, private val dir: File, private val maxBytes: Long) {

    private class Piece(val start: Long, val file: File) {
        @Volatile var length = 0L
        var reader: RandomAccessFile? = null
    }

    val id: String = Integer.toHexString(System.identityHashCode(this)) + SystemClock.elapsedRealtime().toString(36)
    private val lock = Object()
    private val pieces = ArrayList<Piece>()
    @Volatile var written = 0L; private set
    @Volatile var firstAvailable = 0L; private set
    @Volatile var closed = false; private set
    @Volatile var failure: String? = null; private set
    @Volatile var reconnects = 0; private set
    @Volatile var lastDataAt = SystemClock.elapsedRealtime(); private set
    private val startedAt = SystemClock.elapsedRealtime()
    private var firstDataAt = 0L
    private var out: FileOutputStream? = null
    @Volatile private var input: java.io.InputStream? = null
    private val fetcher = UrlConnectionFetcher(Repository.USER_AGENT, connectTimeoutMs = 10_000, readTimeoutMs = 10_000)

    private val thread = Thread({ run() }, "live-buffer").apply { isDaemon = true }

    init {
        dir.mkdirs()
        REGISTRY[id] = this
        thread.start()
    }

    /** Average bytes per second of this channel; 0 until a few seconds have arrived. */
    fun byteRate(): Double {
        if (firstDataAt == 0L) return 0.0
        val secs = (SystemClock.elapsedRealtime() - firstDataAt) / 1000.0
        return if (secs < 2 || written <= 0) 0.0 else written / secs
    }

    /** Seconds held in the buffer (how far back you can rewind). */
    fun heldSeconds(): Long = byteRate().let { r -> if (r <= 0) 0 else ((written - firstAvailable) / r).toLong() }

    fun secondsSinceData(): Long = (SystemClock.elapsedRealtime() - lastDataAt) / 1000

    private fun run() {
        val buf = ByteArray(64 * 1024)
        var failuresInARow = 0
        while (!closed) {
            try {
                fetcher.open(url).use { stream ->
                    input = stream
                    while (!closed) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (n > 0) { append(buf, n); failuresInARow = 0 }
                    }
                }
                if (closed) break
                throw IOException("Stream ended")
            } catch (e: Exception) {
                if (closed) break
                failuresInARow++
                reconnects++
                val refused = e is lumen.core.HttpException
                DiagLog.log("Live buffer: connection dropped (${if (refused) "HTTP ${(e as lumen.core.HttpException).code}" else e.javaClass.simpleName}); try $failuresInARow")
                // Providers block devices that reconnect too often, so back off hard and give up early.
                // A refusal (e.g. connection limit reached) or 5 failures in a row ends this buffer;
                // the player then falls back to playing the channel directly.
                if (refused || failuresInARow >= 5 || (written == 0L && failuresInARow >= 3)) {
                    failure = e.message ?: "Couldn't open the channel"
                    synchronized(lock) { lock.notifyAll() }
                    break
                }
                val wait = (2_000L shl (failuresInARow - 1)).coerceAtMost(20_000) // 2, 4, 8, 16 s
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun append(b: ByteArray, n: Int) {
        synchronized(lock) {
            var piece = pieces.lastOrNull()
            if (piece == null || piece.length >= PIECE) {
                runCatching { out?.close() }
                piece = Piece(written, File(dir, "$id-${pieces.size}.ts"))
                pieces.add(piece)
                out = FileOutputStream(piece.file)
            }
            out!!.write(b, 0, n)
            piece.length += n
            written += n
            if (firstDataAt == 0L) firstDataAt = SystemClock.elapsedRealtime()
            lastDataAt = SystemClock.elapsedRealtime()
            // Drop the oldest piece once we're over the limit.
            while (pieces.size > 2 && written - pieces[1].start > maxBytes) {
                val old = pieces.removeAt(0)
                runCatching { old.reader?.close() }
                old.file.delete()
                firstAvailable = pieces[0].start
            }
            lock.notifyAll()
        }
    }

    /**
     * Reads at absolute [pos], waiting for live data to arrive. Returns -1 when the
     * buffer is closed. A position that's already been deleted jumps to the oldest data.
     */
    fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            while (pos >= written && !closed && failure == null) {
                try { lock.wait(500) } catch (e: InterruptedException) { throw InterruptedIOException() }
            }
            if (closed) return -1
            failure?.let { if (pos >= written) throw IOException(it) }
            val p = maxOf(pos, firstAvailable)
            val piece = pieces.lastOrNull { it.start <= p } ?: return -1
            val inPiece = p - piece.start
            val avail = (piece.length - inPiece).coerceAtLeast(0)
            if (avail == 0L) return 0
            val r = piece.reader ?: RandomAccessFile(piece.file, "r").also { piece.reader = it }
            r.seek(inPiece)
            return r.read(b, off, minOf(len.toLong(), avail).toInt())
        }
    }

    fun close() {
        closed = true
        REGISTRY.remove(id)
        synchronized(lock) {
            lock.notifyAll()
            runCatching { out?.close() }
            pieces.forEach { runCatching { it.reader?.close() }; it.file.delete() }
            pieces.clear()
        }
        runCatching { input?.close() } // frees the provider connection right away
        thread.interrupt()
    }

    /** URL the player opens to play from [offset] bytes into this buffer. */
    fun playUrl(offset: Long): String = "$SCHEME://$id?from=${offset - offset % 188}"

    companion object {
        const val SCHEME = "lumen-buffer"
        private const val PIECE = 32L * 1024 * 1024
        internal val REGISTRY = ConcurrentHashMap<String, LiveBuffer>()

        /** Deletes anything left over from a previous run. */
        fun cleanup(dir: File) {
            dir.listFiles()?.forEach { f -> if (REGISTRY.keys.none { f.name.startsWith("$it-") }) f.delete() }
        }
    }
}

/** Media3 data source reading from a [LiveBuffer]. */
class BufferDataSource : BaseDataSource(false) {
    private var buffer: LiveBuffer? = null
    private var pos = 0L
    private var uri: android.net.Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val b = LiveBuffer.REGISTRY[dataSpec.uri.host ?: ""] ?: throw IOException("Live buffer closed")
        buffer = b
        pos = (dataSpec.uri.getQueryParameter("from")?.toLongOrNull() ?: 0L) + dataSpec.position
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = this.buffer ?: return C.RESULT_END_OF_INPUT
        if (pos < b.firstAvailable) pos = b.firstAvailable // rewound past what's kept
        var n = b.read(pos, buffer, offset, length)
        while (n == 0) n = b.read(pos, buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri() = uri

    override fun close() {
        if (buffer != null) { buffer = null; transferEnded() }
    }
}
