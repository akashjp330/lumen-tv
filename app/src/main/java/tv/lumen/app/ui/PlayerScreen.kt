@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.lumen.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C as MediaC
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import kotlinx.coroutines.delay
import lumen.core.Catchup
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.EpgMatcher
import tv.lumen.app.MainViewModel
import tv.lumen.app.PlayRequest
import tv.lumen.app.Route
import tv.lumen.app.player.PlayerFactory

private enum class PlayerDialog { TRACKS, FIX_GUIDE }

@Composable
fun PlayerScreen(vm: MainViewModel, initial: PlayRequest) {
    val ctx = LocalContext.current
    val repo by vm.repo.state.collectAsStateWithLifecycle()
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val now by rememberNow(15_000)

    var req by remember { mutableStateOf(initial) }
    val player = remember { PlayerFactory.create(ctx) }
    var previousKey by remember {
        mutableStateOf(prefs.recent.firstOrNull { it != initial.channelKey })
    }
    var overlay by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    val rootFr = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }

    val visible = remember(repo.catalog, prefs.hiddenCats, prefs.hiddenChannels) {
        repo.catalog?.let { vm.repo.visibleChannels(it) }.orEmpty()
    }
    val allChannels = repo.catalog?.channels.orEmpty()
    fun channelOf(key: String?): Channel? =
        key?.let { k -> visible.firstOrNull { it.key == k } ?: allChannels.firstOrNull { it.key == k } }
    val channel = channelOf(req.channelKey)

    // ---------- player lifecycle ----------
    val latestReq by rememberUpdatedState(req)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) failed = null
                if (state == Player.STATE_ENDED && !latestReq.isLive) {
                    vm.saveProgress(latestReq, player.duration, player.duration)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "The provider refused the stream. You may be at your connection limit, or the channel is down."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem. Reconnecting…"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This stream's format isn't supported on this device."
                    else -> "Playback problem (${error.errorCodeName}). Reconnecting…"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            val r = latestReq
            if (!r.isLive) vm.saveProgress(r, player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(req.url) {
        failed = null
        PlayerFactory.play(player, req.url, req.startPositionMs)
    }

    // position ticker + periodic progress save
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(1_000)
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0
            if (++n % 15 == 0 && !latestReq.isLive) vm.saveProgress(latestReq, position, duration)
        }
    }

    // auto-hide the overlay
    LaunchedEffect(overlay, touch, dialog) {
        if (overlay && dialog == null) {
            delay(6_000)
            if (player.isPlaying) overlay = false
        }
    }
    LaunchedEffect(overlay, dialog) {
        delay(50)
        if (dialog == null) runCatching { (if (overlay) firstButton else rootFr).requestFocus() }
    }

    fun switchTo(ch: Channel) {
        if (vm.isLocked(ContentType.LIVE, ch.categoryId)) { vm.playChannel(ch, replace = true); return }
        if (ch.key != req.channelKey) previousKey = req.channelKey
        vm.store.pushRecent(ch.key)
        req = vm.channelRequest(ch)
        overlay = true
        touch++
    }

    fun zap(delta: Int) {
        val cur = channel ?: return
        val list = visible.filter { it.categoryId == cur.categoryId }.ifEmpty { visible }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == cur.key }.coerceAtLeast(0)
        switchTo(list[(i + delta).mod(list.size)])
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_600)
        val n = digits.toIntOrNull()
        digits = ""
        val target = visible.firstOrNull { it.number == n }
        if (target != null) switchTo(target) else vm.showBanner("No channel $n")
    }

    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }

    fun handleKey(e: KeyEvent): Boolean {
        val code = e.nativeKeyEvent.keyCode
        val down = e.type == KeyEventType.KeyDown
        touch++
        // Media keys work whether or not the overlay is showing.
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) togglePlay(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down && !req.isLive) player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0)); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down && !req.isLive) player.seekTo(player.currentPosition + 30_000); return true }
            AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { if (down && req.isLive) zap(+1); return true }
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down && req.isLive) zap(-1); return true }
        }
        if (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 && req.isLive && dialog == null) {
            if (down) digits = (digits + (code - AndroidKeyEvent.KEYCODE_0)).take(5)
            return true
        }
        if (overlay || dialog != null) return false // let focus move between buttons
        if (!down) return code != AndroidKeyEvent.KEYCODE_BACK
        if (req.isLive) {
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> zap(+1)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> zap(-1)
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> channelOf(previousKey)?.let { switchTo(it) } ?: vm.showBanner("No previous channel yet")
                AndroidKeyEvent.KEYCODE_BACK -> return false
                else -> overlay = true
            }
        } else {
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { togglePlay(); overlay = true }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); overlay = true }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekTo(player.currentPosition + 30_000); overlay = true }
                AndroidKeyEvent.KEYCODE_BACK -> return false
                else -> overlay = true
            }
        }
        return true
    }

    BackHandler(enabled = overlay || dialog != null) {
        if (dialog != null) dialog = null else overlay = false
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { handleKey(it) }
            .focusRequester(rootFr)
            .focusable()
    ) {
        VideoSurface(player, Modifier.fillMaxSize())

        if (buffering && failed == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        failed?.let {
            Text(it, style = T.body.copy(color = Color.White), modifier = Modifier.align(Alignment.Center)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(16.dp))
        }
        if (digits.isNotEmpty()) {
            Text(digits, style = T.hero.copy(fontSize = 44.sp), modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 6.dp))
        }

        if (overlay && dialog == null) {
            Box(
                Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00B0D12))))
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 40.dp, bottom = 28.dp).fillMaxWidth()) {
                if (req.isLive && channel != null) {
                    LiveInfo(channel, repo.guide.at(channel.key, now), repo.guide.next(channel.key, now), now)
                } else {
                    VodInfo(req, position, duration)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (req.isLive && channel != null) {
                        val fav = channel.key in prefs.favorites
                        PillButton(if (fav) "★ Favorite" else "☆ Add favorite", onClick = { vm.store.toggleFavorite(channel.key) },
                            modifier = Modifier.focusRequester(firstButton))
                        val p = repo.guide.at(channel.key, now)
                        if (p != null && channel.catchupDays > 0 && Catchup.isAvailable(channel, p, now)) {
                            PillButton("Start over", onClick = { vm.playCatchup(channel, p) })
                        }
                        PillButton("Guide", onClick = { vm.go(Route.Guide) })
                        PillButton("Audio & subtitles", onClick = { dialog = PlayerDialog.TRACKS })
                        PillButton("Multiview", onClick = {
                            val others = (prefs.favorites + prefs.recent).distinct().filter { it != channel.key }.take(3)
                            vm.go(Route.Multiview(listOf(channel.key) + others))
                        })
                        PillButton("Fix guide match", onClick = { dialog = PlayerDialog.FIX_GUIDE })
                    } else {
                        PillButton(if (playing) "Pause" else "Play", onClick = { togglePlay() }, primary = true,
                            modifier = Modifier.focusRequester(firstButton))
                        PillButton("−10 s", onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) })
                        PillButton("+30 s", onClick = { player.seekTo(player.currentPosition + 30_000) })
                        PillButton("Restart", onClick = { player.seekTo(0) })
                        PillButton("Audio & subtitles", onClick = { dialog = PlayerDialog.TRACKS })
                        if (req.kind == PlayRequest.Kind.CATCHUP) {
                            channel?.let { ch -> PillButton("Go live", onClick = { vm.playChannel(ch, replace = true) }) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (req.isLive) "▲ ▼ change channel  ·  ◀ previous channel  ·  0–9 type a channel number  ·  Back hides this"
                    else "◀ back 10 s  ·  ▶ ahead 30 s  ·  OK pause  ·  Back hides this",
                    style = T.meta,
                )
            }
        }

        when (dialog) {
            PlayerDialog.TRACKS -> TracksDialog(player) { dialog = null }
            PlayerDialog.FIX_GUIDE -> channel?.let { ch -> FixGuideDialog(vm, ch, repo.xmltv) { dialog = null } }
            null -> Unit
        }
    }
}

@Composable
private fun LiveInfo(ch: Channel, p: lumen.core.Program?, next: lumen.core.Program?, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(ch, Modifier.size(72.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface2), monogramSize = 32.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip()
                Text("${ch.number}  ${ch.name}", style = T.title.copy(color = C.Text2))
            }
            OneLine(p?.title ?: ch.name, T.h1)
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtRange(p), style = T.meta)
                    ProgressBar(p.progress(now), Modifier.width(160.dp))
                    Text(fmtLeft(p, now), style = T.meta)
                    next?.let { OneLine("Next: ${it.title} at ${fmtClock(it.startMs)}", T.meta) }
                }
            }
        }
    }
}

@Composable
private fun VodInfo(req: PlayRequest, position: Long, duration: Long) {
    Column {
        req.subtitle?.let { Text(it, style = T.title.copy(color = C.Text2)) }
        OneLine(req.title, T.h1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(fmtPosition(position), style = T.meta)
            ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), height = 4.dp)
            Text(if (duration > 0) fmtPosition(duration) else "--:--", style = T.meta)
        }
    }
}

@Composable
private fun DialogFrame(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(360.dp).padding(24.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(18.dp),
        ) {
            Text(title, style = T.h2)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

private data class TrackOption(val label: String, val selected: Boolean, val apply: () -> Unit)

@Composable
private fun TracksDialog(player: Player, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val tracks: Tracks = player.currentTracks
    fun options(type: Int): List<TrackOption> = tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            val name = listOfNotNull(f.label, f.language?.uppercase(), f.channelCount.takeIf { it > 0 }?.let { "${it}ch" })
                .distinct().joinToString(" · ").ifBlank { "Track ${i + 1}" }
            TrackOption(name, g.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    val audio = options(MediaC.TRACK_TYPE_AUDIO)
    val text = options(MediaC.TRACK_TYPE_TEXT)
    val textOff = TrackOption("Subtitles off", text.none { it.selected }) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MediaC.TRACK_TYPE_TEXT, true).build()
    }
    DialogFrame("Audio & subtitles") {
        LazyColumn(Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Audio", style = T.meta) }
            if (audio.isEmpty()) item { Text("Only one audio track", style = T.body) }
            items(audio.size) { i -> TrackRow(audio[i], if (i == 0) Modifier.focusRequester(first) else Modifier, onClose) }
            item { Spacer(Modifier.height(6.dp)); Text("Subtitles", style = T.meta) }
            item { TrackRow(textOff, if (audio.isEmpty()) Modifier.focusRequester(first) else Modifier, onClose) }
            items(text.size) { i -> TrackRow(text[i], Modifier, onClose) }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}

@Composable
private fun TrackRow(o: TrackOption, modifier: Modifier, onClose: () -> Unit) {
    Focusable(
        onClick = { o.apply(); onClose() },
        modifier = modifier.fillMaxWidth().height(36.dp),
        color = C.Surface2, focusedColor = C.Text, scale = 1.02f, ring = false,
    ) { f ->
        Text((if (o.selected) "✓  " else "    ") + o.label, style = T.title.copy(color = if (f) C.Ink else C.Text),
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
    }
}

@Composable
private fun FixGuideDialog(vm: MainViewModel, ch: Channel, xmltv: lumen.core.XmltvData?, onClose: () -> Unit) {
    var query by remember { mutableStateOf(EpgMatcher.normalize(ch.name).take(12)) }
    val matcher = remember(xmltv) { xmltv?.let { EpgMatcher(it) } }
    val candidates = remember(query, matcher) { matcher?.candidates(query).orEmpty() }
    val fieldFr = remember { FocusRequester() }
    DialogFrame("Match ${ch.name} to a guide channel") {
        if (matcher == null) {
            Text("The guide hasn't loaded yet. Try again in a minute.", style = T.body)
        } else {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Search guide channels") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = LocalAccent.current),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFr),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    TrackRow(TrackOption("Automatic (reset)", ch.key !in vm.store.prefs.value.epgOverrides) {
                        vm.store.updatePrefs { it.copy(epgOverrides = it.epgOverrides - ch.key) }
                        vm.repo.rebuildGuideMatches()
                    }, Modifier, onClose)
                }
                items(candidates, key = { it.id }) { c ->
                    val label = (c.names.firstOrNull() ?: c.id) + "  (${c.id})"
                    TrackRow(TrackOption(label, vm.store.prefs.value.epgOverrides[ch.key]?.equals(c.id, true) == true) {
                        vm.store.updatePrefs { it.copy(epgOverrides = it.epgOverrides + (ch.key to c.id)) }
                        vm.repo.rebuildGuideMatches()
                    }, Modifier, onClose)
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { fieldFr.requestFocus() } }
}
