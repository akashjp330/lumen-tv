package tv.lumen.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import lumen.core.Channel
import tv.lumen.app.MainViewModel
import tv.lumen.app.player.PlayerFactory

/**
 * Up to four channels at once (each uses one of the account's streams).
 * The focused tile plays sound. OK = full screen, ≡ = change channel.
 */
@Composable
fun MultiviewScreen(vm: MainViewModel, initialKeys: List<String>) {
    val repo by vm.repo.state.collectAsStateWithLifecycle()
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val catalog = repo.catalog ?: return
    val visible = remember(catalog, prefs.hiddenCats) { vm.repo.visibleChannels(catalog) }
    val keys = remember { mutableStateListOf<String>().apply { addAll(initialKeys.take(4)) } }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var pickerFor by remember { mutableStateOf<Int?>(null) }
    val firstFr = remember { FocusRequester() }

    val slots = if (keys.size < 4) keys.size + 1 else 4
    Column(Modifier.fillMaxSize().background(Color.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        val key = keys.getOrNull(i)
                        val ch = key?.let { k -> catalog.channels.firstOrNull { it.key == k } }
                        when {
                            ch != null -> Tile(
                                ch = ch, audible = focusedIndex == i,
                                modifier = if (i == 0) Modifier.focusRequester(firstFr) else Modifier,
                                onFocus = { focusedIndex = i },
                                onClick = { vm.back(); vm.playChannel(ch) },
                                onMenu = { pickerFor = i },
                            )
                            i < slots -> Focusable(
                                onClick = { pickerFor = i },
                                modifier = Modifier.fillMaxSize(),
                                color = C.Surface, focusedColor = C.Raised, scale = 1f,
                            ) {
                                Text("+ Add a channel", style = T.h2.copy(color = C.Muted), modifier = Modifier.align(Alignment.Center))
                            }
                            else -> Spacer(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        Text("OK: full screen  ·  ≡: change channel  ·  sound follows the highlighted tile  ·  Back: exit", style = T.meta,
            modifier = Modifier.padding(start = 6.dp))
    }

    pickerFor?.let { slot ->
        ChannelPicker(
            channels = prefs.favorites.mapNotNull { k -> visible.firstOrNull { it.key == k } } +
                visible.filter { it.key !in prefs.favorites },
            onPick = { ch ->
                if (slot < keys.size) keys[slot] = ch.key else keys.add(ch.key)
                pickerFor = null
            },
            onClose = { pickerFor = null },
        )
    }
    LaunchedEffect(Unit) { delay(100); runCatching { firstFr.requestFocus() } }
}

@Composable
private fun Tile(
    ch: Channel,
    audible: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val ctx = LocalContext.current
    val player: ExoPlayer = remember { PlayerFactory.create(ctx, muted = true, small = true) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(ch.streamUrl) { PlayerFactory.play(player, ch.streamUrl) }
    LaunchedEffect(audible) { player.volume = if (audible) 1f else 0f }
    Focusable(
        onClick = onClick, modifier = modifier.fillMaxSize(), color = Color.Black, scale = 1f,
        onFocus = { if (it) onFocus() }, onMenu = onMenu,
    ) { focused ->
        VideoSurface(player, Modifier.fillMaxSize())
        Text(
            (if (audible) "♪ " else "") + "${ch.number} ${ch.name}",
            style = T.title,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (focused) Box(Modifier.fillMaxSize().border(3.dp, LocalAccent.current))
    }
}

@Composable
fun ChannelPicker(channels: List<Channel>, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(340.dp).fillMaxSize().background(C.Surface).padding(16.dp)) {
            Text("Choose a channel", style = T.h2)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(channels.take(500), key = { it.key }) { ch ->
                    Focusable(
                        onClick = { onPick(ch) },
                        modifier = (if (ch == channels.first()) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(34.dp),
                        color = C.Surface2, focusedColor = C.Text, scale = 1.02f, ring = false,
                    ) { f ->
                        Text("${ch.number}  ${ch.name}", style = T.title.copy(color = if (f) C.Ink else C.Text),
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}
