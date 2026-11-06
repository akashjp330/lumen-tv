package tv.lumen.app.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import tv.lumen.app.data.DiagLog
import java.io.File

/**
 * Runs one live channel in the player: picks TS or HLS, plays through the
 * on-device [LiveBuffer] when live pause is on, watches for freezes, and reports
 * trouble so the screen can switch format or offer another version of the channel.
 */
class LiveController(context: Context, private val player: ExoPlayer) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bufferStartedAt = 0L
    private var bufferErrors = 0

    init {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val b = buffer ?: return
                bufferErrors++
                DiagLog.log("Playback from live buffer failed (${error.errorCodeName}); " +
                    if (bufferErrors >= 3) "switching to direct playback" else "resuming")
                handler.postDelayed({
                    if (buffer !== b) return@postDelayed
                    if (bufferErrors >= 3) fallBackToDirect() else restartAt(b, playbackOffset(b))
                }, 800)
            }
        })
    }

    /** Plays the channel straight from the provider, without the on-device buffer. */
    private fun fallBackToDirect() {
        val u = url
        stop()
        PlayerFactory.play(player, u)
    }

    private val dir = File(context.cacheDir, "timeshift").also { it.mkdirs(); LiveBuffer.cleanup(it) }

    var buffer: LiveBuffer? = null; private set
    /** Byte offset in [buffer] where the current playback began. */
    private var from = 0L
    var url: String = ""; private set
    var format: String = "ts"; private set
    var stalls = 0; private set
    private val troubleTimes = ArrayDeque<Long>()

    private var lastPos = -1L
    private var frozenSince = 0L
    /** The watchdog only runs once the picture has started; slow channel starts aren't freezes. */
    private var started = false

    val usingBuffer get() = buffer != null

    /** Starts (or restarts) a channel. [timeshift] needs the TS format. */
    fun start(streamUrl: String, timeshift: Boolean) {
        stop()
        url = streamUrl
        format = StreamUrls.formatOf(streamUrl)
        troubleTimes.clear(); stalls = 0; lastPos = -1; frozenSince = 0; started = false
        val room = dir.usableSpace - 800L * 1024 * 1024
        if (timeshift && format == "ts" && streamUrl.startsWith("http") && room > 300L * 1024 * 1024) {
            val b = LiveBuffer(streamUrl, dir, maxBytes = room.coerceAtMost(MAX_BUFFER))
            buffer = b
            from = 0
            bufferStartedAt = SystemClock.elapsedRealtime()
            bufferErrors = 0
            PlayerFactory.play(player, b.playUrl(0))
        } else {
            PlayerFactory.play(player, streamUrl)
        }
    }

    fun stop() {
        buffer?.close()
        buffer = null
    }

    // ---------- live pause & rewind ----------

    /** Bytes into the buffer that the picture is showing now. */
    private fun playbackOffset(b: LiveBuffer): Long {
        val rate = b.byteRate()
        return (from + (player.currentPosition / 1000.0 * rate).toLong()).coerceIn(b.firstAvailable, b.written)
    }

    /** Seconds behind live; 0 when watching live. */
    fun behindLive(): Long {
        val b = buffer ?: return 0
        val rate = b.byteRate()
        if (rate <= 0) return 0
        return ((b.written - playbackOffset(b)) / rate).toLong().coerceAtLeast(0)
    }

    /** How many seconds you can go back. */
    fun rewindable(): Long = buffer?.heldSeconds() ?: 0

    /** Jumps by [seconds] (negative = back). Returns false when it can't. */
    fun seekBy(seconds: Int): Boolean {
        val b = buffer ?: return false
        val rate = b.byteRate()
        if (rate <= 0) return false
        val target = (playbackOffset(b) + (seconds * rate).toLong())
            .coerceIn(b.firstAvailable, (b.written - (2 * rate).toLong()).coerceAtLeast(b.firstAvailable))
        restartAt(b, target)
        return true
    }

    fun goLive() {
        val b = buffer ?: return
        val rate = b.byteRate()
        restartAt(b, (b.written - (3 * rate).toLong()).coerceAtLeast(b.firstAvailable))
    }

    private fun restartAt(b: LiveBuffer, offset: Long) {
        val keepPlaying = player.playWhenReady
        from = offset - offset % 188
        player.setMediaItem(PlayerFactory.item(b.playUrl(from)))
        player.prepare()
        player.playWhenReady = keepPlaying
        lastPos = -1; frozenSince = 0
    }

    // ---------- freeze watchdog ----------

    /**
     * Call once a second. Returns true when the picture has been frozen long
     * enough to count as trouble; the stream is restarted automatically.
     */
    fun tick(): Boolean {
        // The buffer couldn't get this channel going: play it directly instead.
        buffer?.let { b ->
            if (b.failure != null || (b.written == 0L && SystemClock.elapsedRealtime() - bufferStartedAt > 12_000)) {
                DiagLog.log("Live buffer got no data; playing the channel directly")
                fallBackToDirect()
                return false
            }
        }
        if (!player.playWhenReady) { frozenSince = 0; lastPos = player.currentPosition; return false }
        val pos = player.currentPosition
        if (!started) { if (pos > 0 && player.isPlaying) started = true; lastPos = pos; return false }
        val now = SystemClock.elapsedRealtime()
        val moving = pos != lastPos
        lastPos = pos
        if (moving) { frozenSince = 0; return false }
        if (frozenSince == 0L) { frozenSince = now; return false }
        if (now - frozenSince < FREEZE_MS) return false
        frozenSince = 0
        stalls++
        DiagLog.log("Picture frozen ${FREEZE_MS / 1000}s; restarting stream (${if (usingBuffer) "buffered" else format})")
        val b = buffer
        if (b != null) {
            // The buffer reconnects by itself; if it's still receiving, the player is the stuck part.
            if (b.secondsSinceData() < 5) restartAt(b, playbackOffset(b))
        } else {
            player.prepare(); player.play()
        }
        return noteTrouble()
    }

    /** Records a playback problem; true when there have been 2+ in the last 3 minutes. */
    fun noteTrouble(): Boolean {
        val now = SystemClock.elapsedRealtime()
        troubleTimes.addLast(now)
        while (troubleTimes.isNotEmpty() && now - troubleTimes.first() > 180_000) troubleTimes.removeFirst()
        return troubleTimes.size >= 2
    }

    fun clearTrouble() = troubleTimes.clear()

    companion object {
        private const val FREEZE_MS = 8_000L
        private const val MAX_BUFFER = 3L * 1024 * 1024 * 1024 // about an hour of HD
    }
}
