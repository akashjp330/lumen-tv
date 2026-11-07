package tv.lumen.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * Two, three or four channels at once. Each tile showing a channel uses one of the
 * account's streams; the highlighted tile plays sound. The bar at the bottom acts
 * on the highlighted tile.
 */
@Composable
fun MultiviewScreen(vm: MainViewModel, initialKeys: List<String>) {
    val repo by vm.repo.state.collectAsStateWithLifecycle()
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val catalog = repo.catalog ?: return
    val visible = remember(catalog, prefs.hiddenCats, prefs.hiddenChannels) { vm.repo.visibleChannels(catalog) }
    val byKey = remember(catalog) { catalog.channels.associateBy { it.key } }
    val now by rememberNow(30_000)

    // Four slots; the layout decides how many are on screen. "" = empty slot.
    val slots = remember { mutableStateListOf<String>().apply { repeat(4) { add(initialKeys.getOrNull(it) ?: "") } } }
    var layout by remember { mutableIntStateOf(2) }
    var selected by remember { mutableIntStateOf(0) }
    var pickerFor by remember { mutableStateOf<Int?>(null) }
    val tileFr = remember { List(4) { FocusRequester() } }

    BackHandler(enabled = pickerFor != null) { pickerFor = null }

    fun fullScreen(ch: Channel) { vm.back(); vm.playChannel(ch, replace = true) }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            @Composable
            fun slot(i: Int, modifier: Modifier) {
                val ch = byKey[slots[i]]
                Box(modifier) {
                    if (ch != null) key(ch.key) {
                        Tile(
                            ch = ch, audible = selected == i,
                            program = repo.guide.at(ch.key, now)?.title,
                            modifier = Modifier.focusRequester(tileFr[i]),
                            onFocus = { selected = i },
                            onClick = { fullScreen(ch) },
                            onMenu = { pickerFor = i },
                        )
                    } else {
                        Focusable(
                            onClick = { pickerFor = i },
                            modifier = Modifier.fillMaxSize().focusRequester(tileFr[i]),
                            color = C.Surface, focusedColor = C.Raised, scale = 1f,
                            onFocus = { if (it) selected = i },
                        ) {
                            Text("+  Choose a channel", style = T.h2.copy(color = C.Muted), modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
            when (layout) {
                2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    slot(0, Modifier.weight(1f).aspectRatio(16f / 9f))
                    slot(1, Modifier.weight(1f).aspectRatio(16f / 9f))
                }
                3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    slot(0, Modifier.weight(2f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        slot(1, Modifier.weight(1f).fillMaxWidth())
                        slot(2, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in 0 until 2) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        slot(r * 2, Modifier.weight(1f).fillMaxHeight())
                        slot(r * 2 + 1, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }

        // Control bar: acts on the highlighted tile.
        val sel = byKey[slots[selected.coerceAtMost(layout - 1)]]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Screens:", style = T.meta)
            for (n in 2..4) PillButton("$n", selected = layout == n, onClick = {
                layout = n
                if (selected >= n) selected = 0
            })
            Spacer(Modifier.width(16.dp))
            Text(if (sel != null) "Highlighted: ${sel.name}" else "Highlighted: empty", style = T.meta, modifier = Modifier.width(220.dp), maxLines = 1)
            PillButton("Change channel", onClick = { pickerFor = selected })
            if (sel != null) {
                PillButton("Full screen", onClick = { fullScreen(sel) })
                PillButton("Remove", onClick = { slots[selected] = "" })
            }
            PillButton("Exit", onClick = { vm.back() })
        }
        Text("Sound plays from the highlighted screen  ·  OK on a screen: full screen  ·  ≡ on a screen: change channel  ·  " +
            "each screen uses one of your streams", style = T.meta, modifier = Modifier.padding(start = 4.dp))
    }

    pickerFor?.let { slot ->
        MultiviewPicker(
            vm = vm, visible = visible, now = now, guide = repo.guide,
            onPick = { ch ->
                slots[slot] = ch.key
                selected = slot
                pickerFor = null
            },
            onClose = { pickerFor = null },
        )
    }
    LaunchedEffect(pickerFor, layout) {
        if (pickerFor == null) { delay(120); runCatching { tileFr[selected.coerceAtMost(layout - 1)].requestFocus() } }
    }
}

@Composable
private fun Tile(
    ch: Channel,
    audible: Boolean,
    program: String?,
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
        onClick = onClick, modifier = modifier.fillMaxSize(), color = Color.Black, scale = 1f, ring = false,
        onFocus = { if (it) onFocus() }, onMenu = onMenu,
    ) { focused ->
        VideoSurface(player, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomStart).padding(8.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text((if (audible) "♪  " else "") + "${ch.number}  ${ch.name}", style = T.title)
            program?.let { OneLine(it, T.meta.copy(color = C.Text2)) }
        }
        if (focused || audible) Box(Modifier.fillMaxSize().border(if (focused) 3.dp else 1.dp, if (focused) LocalAccent.current else C.Line))
    }
}

/** Channel chooser: your lists and recently watched first, then everything. */
@Composable
private fun MultiviewPicker(
    vm: MainViewModel,
    visible: List<Channel>,
    now: Long,
    guide: lumen.core.Guide,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val byKey = remember(visible) { visible.associateBy { it.key } }
    // Sections: each list, recently watched, then all channels.
    val rows: List<Pair<String?, Channel?>> = remember(visible, prefs.allLists, prefs.recent) {
        val out = ArrayList<Pair<String?, Channel?>>()
        for (l in prefs.allLists) {
            val chans = l.keys.mapNotNull { byKey[it] }
            if (chans.isNotEmpty()) { out += l.name to null; chans.forEach { out += null to it } }
        }
        val recent = prefs.recent.mapNotNull { byKey[it] }.take(20)
        if (recent.isNotEmpty()) { out += "Recently watched" to null; recent.forEach { out += null to it } }
        out += "All channels" to null
        visible.take(1500).forEach { out += null to it }
        out
    }
    val firstIndex = rows.indexOfFirst { it.second != null }
    val first = remember { FocusRequester() }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(400.dp).fillMaxSize().background(C.Surface).padding(16.dp)) {
            Text("Choose a channel", style = T.h2)
            Text("OK to pick · Back to cancel", style = T.meta)
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows.size) { i ->
                    val (header, ch) = rows[i]
                    if (header != null) {
                        Text(header, style = T.meta.copy(color = C.Text2), modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp, bottom = 2.dp))
                    } else if (ch != null) {
                        val p = guide.at(ch.key, now)
                        Focusable(
                            onClick = { onPick(ch) },
                            modifier = (if (i == firstIndex) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(46.dp),
                            color = C.Surface2, focusedColor = C.Text, scale = 1.02f, ring = false,
                        ) { f ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ChannelLogo(ch, Modifier.size(46.dp, 30.dp).clip(RoundedCornerShape(5.dp)).background(C.Ink), monogramSize = 22.dp)
                                Column(Modifier.weight(1f)) {
                                    OneLine("${ch.number}  ${ch.name}", T.title.copy(color = if (f) C.Ink else C.Text))
                                    p?.let { OneLine(it.title, T.meta.copy(color = if (f) C.Ink else C.Muted)) }
                                }
                                QualityBadge(ch.name)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

/** Generic channel chooser used elsewhere (kept for other screens). */
@Composable
fun ChannelPicker(channels: List<Channel>, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(340.dp).fillMaxSize().background(C.Surface).padding(16.dp)) {
            Text("Choose a channel", style = T.h2)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(channels.take(500).size) { i ->
                    val ch = channels[i]
                    Focusable(
                        onClick = { onPick(ch) },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(34.dp),
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
