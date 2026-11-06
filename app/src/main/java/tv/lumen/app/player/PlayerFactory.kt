@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.lumen.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import tv.lumen.app.data.DiagLog
import tv.lumen.app.data.Repository

/** ExoPlayer tuned for IPTV: raw MPEG-TS and HLS, cross-protocol redirects, auto-reconnect, live buffer. */
object PlayerFactory {

    fun create(
        context: Context,
        muted: Boolean = false,
        small: Boolean = false,
        bufferLevel: Int = 1,
        softwareDecoder: Boolean = false,
    ): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Repository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSource = RoutingDataSource.Factory(DefaultDataSource.Factory(context, http))
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        // min, max, to start, to resume after a stall. Resuming after a stall with a larger
        // cushion is what makes "Large"/"Maximum" smoother: playback then runs that far behind
        // live (from the on-device buffer), so the next hiccup is absorbed.
        val (minB, maxB, startB, resumeB) = when {
            small -> listOf(4_000, 12_000, 1_500, 3_000)
            bufferLevel >= 2 -> listOf(30_000, 180_000, 3_000, 15_000)
            bufferLevel == 1 -> listOf(20_000, 90_000, 2_500, 8_000)
            else -> listOf(15_000, 50_000, 2_000, 4_000)
        }
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(minB, maxB, startB, resumeB).build()
        val selector = if (softwareDecoder) MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
        } else MediaCodecSelector.DEFAULT
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(load)
            .build()
            .apply {
                if (muted) volume = 0f
                playWhenReady = true
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(Reconnector(this))
            }
    }

    fun item(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        val path = url.substringBefore('?')
        when {
            path.endsWith(".m3u8", ignoreCase = true) -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.startsWith(LiveBuffer.SCHEME + "://") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return b.build()
    }

    fun play(player: ExoPlayer, url: String, startPositionMs: Long = 0) {
        player.setMediaItem(item(url), startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.play()
    }
}

/** Live channel URLs in the other format your provider offers (Xtream panels serve both). */
object StreamUrls {
    private val liveTs = Regex("""(/live/[^?#]+)\.ts(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val liveHls = Regex("""(/live/[^?#]+)\.m3u8(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun formatOf(url: String): String = if (url.substringBefore('?').endsWith(".m3u8", true)) "hls" else "ts"

    fun canSwitch(url: String) = liveTs.containsMatchIn(url) || liveHls.containsMatchIn(url)

    fun withFormat(url: String, format: String): String = when (format) {
        "hls" -> liveTs.replace(url) { it.groupValues[1] + ".m3u8" + it.groupValues[2] }
        else -> liveHls.replace(url) { it.groupValues[1] + ".ts" + it.groupValues[2] }
    }
}

/** Sends lumen-buffer:// URLs to the on-device live buffer and everything else to the network. */
class RoutingDataSource(private val network: DataSource) : DataSource {
    private var current: DataSource? = null
    private val listeners = ArrayList<TransferListener>()

    class Factory(private val net: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(net.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        network.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == LiveBuffer.SCHEME) BufferDataSource() else network
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() { try { current?.close() } finally { current = null } }
}

/**
 * IPTV streams drop. Retry with backoff instead of showing an error, and jump
 * back to live when an HLS stream falls out of its window.
 */
private class Reconnector(private val player: ExoPlayer) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY) attempts = 0
    }

    override fun onPlayerError(error: PlaybackException) {
        // Playback from the live buffer is recovered by LiveController (it resumes at the right spot).
        if (player.currentMediaItem?.localConfiguration?.uri?.scheme == LiveBuffer.SCHEME) return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (attempts >= 6) {
            DiagLog.log("Playback failed after retries: ${error.errorCodeName}", error)
            return
        }
        attempts++
        val wait = 1_000L * attempts
        DiagLog.log("Stream error ${error.errorCodeName}; reconnecting in ${wait}ms (try $attempts)")
        handler.postDelayed({
            if (player.mediaItemCount > 0) {
                player.prepare()
                player.play()
            }
        }, wait)
    }
}
