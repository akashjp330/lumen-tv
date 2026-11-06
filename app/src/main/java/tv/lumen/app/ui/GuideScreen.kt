package tv.lumen.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lumen.core.Catchup
import lumen.core.Channel
import lumen.core.ContentType
import lumen.core.Program
import tv.lumen.app.MainViewModel
import tv.lumen.app.data.RepoState
import tv.lumen.app.data.FAVORITES_ID
import tv.lumen.app.data.catKey
import kotlinx.coroutines.delay

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 4 * HALF_HOUR

private data class Focused(val ch: Channel, val p: Program?)

@Composable
fun GuideScreen(vm: MainViewModel, repo: RepoState) {
    val catalog = repo.catalog ?: return
    val prefs by vm.store.prefs.collectAsStateWithLifecycle()
    val now by rememberNow(30_000)
    val guide = repo.guide

    val visible = remember(catalog, prefs.hiddenCats, prefs.hiddenChannels) { vm.repo.visibleChannels(catalog) }
    val byKey = remember(visible) { visible.associateBy { it.key } }
    val counts = remember(visible) { visible.groupingBy { it.categoryId }.eachCount() }
    var filter by remember {
        mutableStateOf(vm.focusMemory["guideFilter"] ?: when {
            prefs.favorites.isNotEmpty() -> "list:$FAVORITES_ID"
            prefs.recent.isNotEmpty() -> "recent"
            else -> "all"
        })
    }
    var pickingCategory by remember { mutableStateOf(false) }
    fun choose(f: String) { filter = f; vm.focusMemory["guideFilter"] = f }
    val categories = remember(catalog, prefs.hiddenCats, counts) {
        catalog.liveCategories.filter { catKey(ContentType.LIVE, it.id) !in prefs.hiddenCats && (counts[it.id] ?: 0) > 0 }
    }
    val rows = remember(visible, filter, prefs.allLists, prefs.recent, prefs.watchSec) {
        when {
            filter == "all" -> visible
            filter == "recent" -> prefs.recent.mapNotNull { byKey[it] }
            filter == "most" -> prefs.mostWatched().mapNotNull { byKey[it.first] }
            filter.startsWith("list:") -> prefs.allLists.firstOrNull { "list:" + it.id == filter }?.keys.orEmpty().mapNotNull { byKey[it] }
            filter.startsWith("cat:") -> visible.filter { "cat:" + it.categoryId == filter }
            else -> visible
        }
    }
    // Lists and history: channel up/down in the player stays inside this list.
    val zap = remember(rows, filter) { if (filter == "all" || filter.startsWith("cat:")) null else rows.map { it.key } }
    val categoryName = categories.firstOrNull { "cat:" + it.id == filter }?.let { displayCategory(it.name) }

    val maxBack = (rows.maxOfOrNull { it.catchupDays } ?: 0).coerceAtMost(3) * 86_400_000L
    var windowStart by remember { mutableLongStateOf(now - now % HALF_HOUR) }
    val minStart = (now - maxBack) - (now - maxBack) % HALF_HOUR
    val maxStart = now + 2 * 86_400_000L
    var focused by remember { mutableStateOf<Focused?>(null) }
    val reminderStarts = remember(prefs.reminders) {
        prefs.reminders.groupBy { it.channelKey }.mapValues { (_, l) -> l.map { it.startMs }.toSet() }
    }

    val restoreKey = vm.focusMemory["guide"] ?: rows.firstOrNull()?.key
    var restored by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst { it.key == restoreKey }.coerceAtLeast(0).let { (it - 2).coerceAtLeast(0) }
    )

    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 20.dp, end = 20.dp)) {
        // Header + details of the focused program
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Live guide", style = T.h1)
                Text("${fmtDay(now)} · ${fmtClock(now)}", style = T.meta)
            }
            RefreshButton(vm, repo)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(prefs.allLists, key = { it.id }) { l ->
                PillButton("${l.name} · ${l.keys.size}", onClick = { choose("list:" + l.id) }, selected = filter == "list:" + l.id)
            }
            item { PillButton("Recently watched · ${prefs.recent.size}", onClick = { choose("recent") }, selected = filter == "recent") }
            item { PillButton("Most watched", onClick = { choose("most") }, selected = filter == "most") }
            item { PillButton("All · ${visible.size}", onClick = { choose("all") }, selected = filter == "all") }
            item {
                PillButton(if (categoryName != null) "$categoryName ▾" else "Categories ▾",
                    onClick = { pickingCategory = true }, selected = categoryName != null)
            }
        }
        Spacer(Modifier.height(10.dp))
        DetailStrip(vm, focused, now, Modifier.fillMaxWidth().height(58.dp))
        if (rows.isEmpty()) {
            Text(when {
                filter == "recent" -> "Channels you watch will show up here."
                filter == "most" -> "After you've watched a few channels, the ones you watch most show up here — a good guide to what belongs in Favorites."
                filter.startsWith("list:") -> "This list is empty. Press ≡ on any channel to add it."
                else -> "No channels here."
            }, style = T.body)
        }
        val guideError = repo.guideError
        if (guideError != null && !guide.hasAnyGuide(rows)) Text(guideError, style = T.meta.copy(color = C.Live))
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val channelCol = 150.dp
            val gridWidth: Dp = maxWidth - channelCol
            val dpPerMs = gridWidth.value / WINDOW

            Column(Modifier.fillMaxSize()) {
                // time header
                Row(Modifier.height(22.dp)) {
                    Spacer(Modifier.width(channelCol))
                    for (i in 0 until 4) {
                        Text(fmtClock(windowStart + i * HALF_HOUR), style = T.meta, modifier = Modifier.width(gridWidth / 4))
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val f = focused?.p ?: return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                                if (f.stopMs >= windowStart + WINDOW && windowStart < maxStart) {
                                    windowStart += HALF_HOUR; true
                                } else false
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
                                if (f.startMs <= windowStart && windowStart > minStart) {
                                    windowStart -= HALF_HOUR; true
                                } else false
                            else -> false
                        }
                    },
                ) {
                    items(rows, key = { it.key }) { ch ->
                        GuideRow(
                            vm = vm, ch = ch, programs = guide.window(ch.key, windowStart, windowStart + WINDOW),
                            windowStart = windowStart, dpPerMs = dpPerMs, channelCol = channelCol, now = now,
                            favorite = ch.key in prefs.favorites,
                            zap = zap,
                            watchedSec = if (filter == "most") prefs.watchSec[ch.key] else null,
                            reminders = reminderStarts[ch.key].orEmpty(),
                            requestFocus = !restored && ch.key == restoreKey,
                            onRestored = { restored = true },
                            onFocus = { p ->
                                focused = Focused(ch, p)
                                vm.focusMemory["guide"] = ch.key
                            },
                        )
                    }
                }
            }
            LaunchedEffect(listState.firstVisibleItemIndex, rows) {
                delay(400)
                val i = listState.firstVisibleItemIndex
                if (rows.isNotEmpty()) vm.repo.ensureGuide(rows.subList(i.coerceAtMost(rows.size - 1), (i + 16).coerceAtMost(rows.size)))
            }
            // red "now" line
            if (now in windowStart until windowStart + WINDOW) {
                val x = channelCol + ((now - windowStart) * dpPerMs).dp
                Box(Modifier.offset(x = x, y = 18.dp).width(2.dp).fillMaxHeight().background(C.Live))
            }
        }
    }
    if (pickingCategory) {
        CategoryPicker(
            title = "Live TV categories",
            items = categories.map { Triple(it.id, displayCategory(it.name), counts[it.id] ?: 0) },
            selected = filter.removePrefix("cat:"),
            onPick = { id -> choose("cat:$id"); pickingCategory = false },
            onClose = { pickingCategory = false },
        )
    }
}

private fun lumen.core.Guide.hasAnyGuide(rows: List<Channel>) = rows.any { hasGuide(it.key) }

@Composable
private fun GuideRow(
    vm: MainViewModel,
    ch: Channel,
    programs: List<Program>,
    windowStart: Long,
    dpPerMs: Float,
    channelCol: Dp,
    now: Long,
    favorite: Boolean,
    zap: List<String>?,
    watchedSec: Long?,
    reminders: Set<Long>,
    requestFocus: Boolean,
    onRestored: () -> Unit,
    onFocus: (Program?) -> Unit,
) {
    val windowEnd = windowStart + WINDOW
    val fr = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().height(46.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Focusable(
            onClick = { vm.playChannel(ch, zapKeys = zap) },
            modifier = Modifier.width(channelCol - 6.dp).fillMaxHeight(),
            color = C.Ink, focusedColor = C.Raised, scale = 1f,
            onFocus = { if (it) onFocus(null) },
            onMenu = { vm.openChannelMenu(ch) },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ch.number.toString(), style = T.meta, modifier = Modifier.width(34.dp))
                ChannelLogo(ch, Modifier.width(40.dp).height(28.dp).clip(RoundedCornerShape(5.dp)).background(C.Surface2), monogramSize = 22.dp)
                Spacer(Modifier.width(6.dp))
                Column {
                    OneLine((if (favorite) "★ " else "") + ch.name, T.title.copy(color = C.Text2))
                    if (watchedSec != null) Text(fmtWatched(watchedSec), style = T.meta)
                }
            }
        }
        Spacer(Modifier.width(6.dp))

        if (programs.isEmpty()) {
            val cellMod = Modifier.width(((windowEnd - windowStart) * dpPerMs).dp - 6.dp).fillMaxHeight()
            Focusable(
                onClick = { vm.playChannel(ch, zapKeys = zap) },
                modifier = if (requestFocus) cellMod.focusRequester(fr) else cellMod,
                color = C.Surface, focusedColor = C.Text, scale = 1f, ring = false,
                onFocus = { if (it) onFocus(null) },
            ) { f ->
                Text("No guide information", style = T.meta.copy(color = if (f) C.Ink else C.Faint),
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp))
            }
            if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; onRestored() }
        } else {
        var cursor = windowStart
        val airingIndex = programs.indexOfFirst { it.isAiring(now) }.let { if (it < 0) 0 else it }
        programs.forEachIndexed { i, p ->
            val s = maxOf(p.startMs, windowStart)
            val e = minOf(p.stopMs, windowEnd)
            if (s > cursor) Spacer(Modifier.width(((s - cursor) * dpPerMs).dp))
            cursor = e
            val w = ((e - s) * dpPerMs).dp - 4.dp
            if (w > 0.dp) key(p.startMs) {
                val airing = p.isAiring(now)
                val past = p.stopMs <= now
                val replay = past && Catchup.isAvailable(ch, p, now)
                val reminder = !past && !airing && p.startMs in reminders
                val cellMod = Modifier.width(w).fillMaxHeight()
                Focusable(
                    onClick = {
                        when {
                            airing -> vm.playChannel(ch, zapKeys = zap)
                            replay -> vm.playCatchup(ch, p)
                            past -> vm.playChannel(ch, zapKeys = zap)
                            else -> vm.toggleReminder(ch, p)
                        }
                    },
                    modifier = if (requestFocus && i == airingIndex) cellMod.focusRequester(fr) else cellMod,
                    shape = RoundedCornerShape(8.dp),
                    color = when { airing -> C.Raised; past -> C.Ink; else -> C.Surface },
                    focusedColor = C.Text,
                    scale = 1f, ring = false,
                    onFocus = { if (it) onFocus(p) },
                    onMenu = { vm.openChannelMenu(ch) },
                ) { f ->
                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)) {
                        OneLine(p.title.ifBlank { "Untitled" }, T.title.copy(color = if (f) C.Ink else if (past) C.Faint else C.Text))
                        val tag = when { replay -> "  ·  REPLAY"; reminder -> "  ·  REMINDER SET"; else -> "" }
                        OneLine(fmtRange(p) + tag, T.meta.copy(color = if (f) C.Ink.copy(alpha = 0.7f) else C.Faint))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        if (requestFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; onRestored() }
        }
    }
}

@Composable
private fun DetailStrip(vm: MainViewModel, f: Focused?, now: Long, modifier: Modifier) {
    Column(modifier) {
        if (f == null) {
            Text("Press OK on a program to watch it. Past programs marked Replay can be watched from the start. " +
                "Pick a future program to get a reminder. Press ≡ to favorite a channel.", style = T.meta)
        } else {
        val p = f.p
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p != null && p.isAiring(now)) LiveChip()
            OneLine(p?.title ?: f.ch.name, T.h2)
        }
        val line = if (p == null) "${f.ch.name} · Ch ${f.ch.number}"
        else "${f.ch.name} · ${fmtRange(p)}" + when {
            p.isAiring(now) -> " · ${fmtLeft(p, now)}"
            p.stopMs <= now && Catchup.isAvailable(f.ch, p, now) -> " · Press OK to watch from the start"
            p.startMs > now -> if (vm.hasReminder(f.ch, p)) " · Reminder set (OK to remove)" else " · Press OK for a reminder"
            else -> ""
        }
        Text(line, style = T.meta)
        p?.description?.let { OneLine(it, T.meta.copy(color = C.Text2)) }
        }
    }
}
