@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.lumen.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import tv.lumen.app.data.DiagLog
import tv.lumen.app.data.Repository

/** ExoPlayer tuned for IPTV: raw MPEG-TS and HLS, cross-protocol redirects, auto-reconnect. */
object PlayerFactory {

    fun create(context: Context, muted: Boolean = false, small: Boolean = false): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Repository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = DefaultDataSource.Factory(context, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        val load = if (small) {
            DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 12_000, 1_500, 3_000).build()
        } else {
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
        }
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
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

    fun item(url: String, startPositionMs: Long = 0): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    fun play(player: ExoPlayer, url: String, startPositionMs: Long = 0) {
        player.setMediaItem(item(url), startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.play()
    }
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
