@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.lumen.app.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import lumen.core.Program
import tv.lumen.app.player.PlayerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("h:mm a", Locale.US)
private val clockShort = SimpleDateFormat("h:mm", Locale.US)
private val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.US)

fun fmtClock(ms: Long): String = clock.format(Date(ms))
fun fmtDay(ms: Long): String = dayFmt.format(Date(ms))
fun fmtRange(p: Program): String = "${clockShort.format(Date(p.startMs))} – ${clock.format(Date(p.stopMs))}"

fun fmtDuration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun fmtLeft(p: Program, now: Long) = fmtDuration(p.stopMs - now) + " left"

fun fmtPosition(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Ticks every [periodMs] so "now" lines and progress bars move. */
@Composable
fun rememberNow(periodMs: Long = 30_000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(periodMs)
        value = System.currentTimeMillis()
    }
}

/** A Media3 PlayerView that never takes remote focus. */
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier, zoom: Boolean = false, shape: String? = null) {
    val mode = when (shape) {
        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = mode
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player
            }
        },
        update = { it.player = player; it.resizeMode = mode },
        modifier = modifier,
    )
}

/** Muted live preview for the home hero; uses one of the account's streams while shown. */
@Composable
fun LivePreview(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember { PlayerFactory.create(ctx, muted = true, small = true) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(url) { PlayerFactory.play(player, url) }
    VideoSurface(player, modifier, zoom = false)
}
